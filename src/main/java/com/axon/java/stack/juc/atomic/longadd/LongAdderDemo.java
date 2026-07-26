package com.axon.java.stack.juc.atomic.longadd;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 性能测试
 */

class AdderDemo {

    public volatile long value;

    /**
     * synchronized 方法
     *
     * 	•	速度: 在多线程情况下，synchronized 方法的性能最差。
     * 	•	底层原理: synchronized 会在每次访问共享资源时加锁和解锁。由于锁是独占的，线程需要等待锁的释放，导致较高的上下文切换和线程阻塞开销。
     * 	•	适用场景: 适合在并发量较低的场景下使用，或者在需要确保操作的原子性和可见性时使用。
     */
    public synchronized void add() {
        value++;
    }

    /**
     * AtomicLong
     *
     * 	•	速度: 比 synchronized 快，但在高并发情况下性能会下降。
     * 	•	底层原理: AtomicLong 基于 CAS（Compare-And-Swap）操作，利用无锁算法来保证原子性操作。CAS 避免了加锁和线程切换，但在高并发情况下可能会因为 CAS 失败而不断重试，从而影响性能。
     * 	•	适用场景: 适合并发更新频率较低或对性能要求不特别高的场景。
     */
    public AtomicLong longValue = new AtomicLong(0);
    public void addAtomicLong() {
        longValue.incrementAndGet();
    }


    /**
     * LongAdder
     *
     * 	•	速度: 在高并发场景下表现出色，比 AtomicLong 快。
     * 	•	底层原理: LongAdder 通过将内部的计数器分散到多个单独的变量（Cell），在高并发时减少竞争。最终值通过求和这些变量来得到。由于减少了热点竞争，它在高并发场景下比 AtomicLong 更有效率。
     * 	•	适用场景: 适合在高并发下频繁更新计数器的场景，如统计请求数、访问量等。
     */
    public LongAdder longAdder = new LongAdder();
    public void longAdderTest() {
        longAdder.increment();
    }


    /**
     *  LongAccumulator
     *
     * 	•	速度: 与 LongAdder 类似，性能较高，具体速度取决于定义的累加函数。
     * 	•	底层原理: LongAccumulator 和 LongAdder 类似，但它可以指定自定义的累加函数（如加法、乘法等），而不仅仅限于加法。这使得它更加灵活，可以用于更复杂的累加场景。
     * 	•	适用场景: 适合在高并发下需要执行自定义操作的计数场景，如求最大值、最小值、平均值等。
     */
    public LongAccumulator longAccumulator = new LongAccumulator((x, y) -> x + y, 0);
    public void longAccumulatorTest() {
        longAccumulator.accumulate(1);
    }

}


/**
 *      synchronized 适合需要严格同步的少量更新场景。
 * 	•	AtomicLong 适合中低并发场景。
 * 	•	LongAdder 适合高并发的累加操作，且需要较高的性能。
 * 	•	LongAccumulator 适合高并发且需要自定义累加操作的场景。
 *
 *
 *
 * > **AtomicLong 的问题：** 所有线程争抢同一个 value，高并发下 CAS 自旋空转，CPU 打满。
 * >
 * > **LongAdder 的思路：** 分散热点——内部维护一个 `base` + `Cell[]` 数组。无竞争时直接 CAS `base`；有竞争时，每个线程 hash 到不同 Cell，各自 CAS 自己的 Cell。求和时把 base + 所有 Cell 累加。
 * >
 * > **三个关键设计：**
 * > 1. `@Contended` 注解防止 Cell 伪共享；
 * > 2. CAS spinlock（`cellsBusy`）保护扩容，不用 synchronized；
 * > 3. 发生冲突才扩容，Cell 数最多到 CPU 核数。
 *
 * ---
 *
 * ## 一句话总结
 *
 * > **AtomicLong 所有人挤一个窗口，LongAdder 开多个窗口分散排队，最后算总账。**
 *
 * ---
 *
 * ## 可以画在纸上的图
 *
 * ```
 * AtomicLong:          LongAdder:
 *
 *  N个线程 → [value]   线程0 → Cell[0] ─┐
 *                      线程1 → Cell[1]  │
 *    所有线程          线程2 → Cell[2]  ├→ sum() = base + Σcells
 *   CAS 同一个值        线程3 → Cell[3] ─┘
 *
 *    星星挤            星星散开各加各的
 * ```
 *
 * ---
 *
 * ## 可能被追问的点
 *
 * | 追问 | 回答 |
 * |------|------|
 * | **sum() 是精确的吗？** | 不是原子快照，遍历时有并发写在发生，适合 QPS 计数等允许微小误差的场景 |
 * | **Cell 什么时候扩容？** | 当前线程 CAS 自己 Cell 失败 2 次就触发，翻倍到 CPU 核数为止 |
 * | **hash 到哪个 Cell？** | 用 `ThreadLocalRandom.probe` 的 `getProbe()`，相当于线程 ID 的 hash，不用 ThreadLocal 免了内存泄漏风险 |
 * | **和 AtomicLong 怎么选？** | 需要精确瞬时值用 AtomicLong，统计聚合用 LongAdder |
 */
public class LongAdderDemo {

    static int ThreadNums = 50;
    static  int  M1=1000000;

    public static void main(String[] args) throws InterruptedException {
        AdderDemo adder = new AdderDemo();
        CountDownLatch latch1 = new CountDownLatch(ThreadNums);
        CountDownLatch latch2 = new CountDownLatch(ThreadNums);
        CountDownLatch latch3 = new CountDownLatch(ThreadNums);
        CountDownLatch latch4 = new CountDownLatch(ThreadNums);
        long beginTime = System.currentTimeMillis();
        long endTime = 0L;

        for (int i = 0; i < ThreadNums; i++) {
            new Thread(() -> {

                for (int j = 0; j < M1; j++) {
                    adder.add();
                }
                latch1.countDown();
            }).start();
        }
        latch1.await();
        endTime = System.currentTimeMillis();
        System.out.println("latch1总共耗时" + (endTime - beginTime) + ",当前结果值" + adder.value);


        beginTime = System.currentTimeMillis();
        for (int i = 0; i < ThreadNums; i++) {
            new Thread(() -> {

                for (int j = 0; j < M1; j++) {
                    adder.addAtomicLong();
                }
                latch2.countDown();
            }).start();
        }
        latch2.await();
        endTime = System.currentTimeMillis();
        System.out.println("latch2总共耗时" + (endTime - beginTime) + ",当前结果值" + adder.longValue.longValue());



        beginTime = System.currentTimeMillis();
        for (int i = 0; i < ThreadNums; i++) {
            new Thread(() -> {

                for (int j = 0; j < M1; j++) {
                    adder.longAdderTest();
                }
                latch3.countDown();
            }).start();
        }
        latch3.await();
        endTime = System.currentTimeMillis();
        System.out.println("latch3总共耗时" + (endTime - beginTime) + ",当前结果值" + adder.longAdder.longValue());


        beginTime = System.currentTimeMillis();
        for (int i = 0; i < ThreadNums; i++) {
            new Thread(() -> {
                for (int j = 0; j < M1; j++) {
                    adder.longAccumulatorTest();
                }
                latch4.countDown();
            }).start();
        }
        latch4.await();
        endTime = System.currentTimeMillis();
        System.out.println("latch4总共耗时" + (endTime - beginTime) + ",当前结果值" + adder.longAccumulator.longValue());


    }
}
