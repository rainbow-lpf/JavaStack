package com.axon.java.stack.juc.Volatile;

import java.util.concurrent.atomic.AtomicInteger;

/***
 *
 * JMM 保证程序运行的 可见性、原子性、有序性。
 *
 * volatile 保证线程的可见性
 *
 * volatile 被修饰后，禁止指令重排序
 *
 * volatile 是不保障他的原子性的
 *
 *  案例： 比如三个线程A、B、C同时操作一个值， A操作完之后，准备更新主内存的值，同时也通知了其他线程，但是由于cpu的运行调度，其它线程此时在挂起的，收到通知失败，那他会继续更新。 那这样会导致数据丢失。因为B、C 更新进去的可能是原来的值，覆盖了A线程的值。导致
 *  最终结果会减少
 *
 *
 *  volatile 并不能保证操作的原子性。操作 numbers++ 由三步组成：读取 numbers 的值、递增、写回内存。由于这三步不是原子操作，因此多个线程可能会在同一时间读取相同的值，然后同时递增，最后的结果会导致一些递增操作的结果被覆盖，从而导致总和减少。
 *
 *  使用AtomicInteger 可解决遇到的原子性问题
 *
 *
 *
 *  操作 numbers++ 并不是一个单一的原子操作，它实际上分为以下几个步骤：
 *
 * 	1.	读取 numbers 的当前值。
 * 	2.	在当前值上加 1。
 * 	3.	将结果写回 numbers。
 *
 * 即使 numbers 被 volatile 修饰，这三个步骤仍然是分离的，不能保证在多线程环境下是连续的。问题的根本在于，即使 volatile 保证了最新的值对其他线程可见，但它无法保证其他线程在修改前获取到的值是最新的。以下是一个可能的场景：
 *
 * 	1.	线程 A 读取了 numbers 的值，假设为 10。
 * 	2.	线程 B 也读取了 numbers 的值，也为 10。
 * 	3.	线程 A 进行 numbers++ 操作，将值更新为 11 并写回。
 * 	4.	线程 B 也进行 numbers++ 操作，仍然将值更新为 11 并写回。
 *
 * 在这个过程中，numbers 的最终值被两次更新为 11，但实际上应该是 12。两个线程的操作之间是竞争关系，volatile 只能保证可见性，但不能防止多个线程同时操作的冲突。每个线程都读取并更新了 numbers，但因为这些操作不是原子性的，导致了最终结果不正确。
 *
 * 为什么可见性不足以解决问题
 *
 * 虽然 volatile 能保证一个线程对变量的修改对其他线程可见，但它无法保证在另一个线程读取和更新该变量之间，没有其他线程修改该变量。这样，线程 B 在执行 numbers++ 时，尽管在读取时已经看到了线程 A 的修改，但由于中间存在时间差，它实际上是基于旧值进行操作。
 *
 * 解决方法
 *
 * 为了保证操作的原子性，可以使用以下方法：
 *
 * 	1.	同步块（synchronized）：通过同步块包围对共享变量的操作，确保同一时刻只有一个线程能够执行这些操作。
 * 	2.	原子类：使用 AtomicInteger 这样的原子类来替代 volatile 修饰的普通整数，这些类提供了一些原子操作方法，比如 incrementAndGet()，能确保在多线程环境下的操作是原子性的。
 *
 * 总之，volatile 能够解决可见性问题，但无法解决多个线程同时操作同一变量的原子性问题。因此，像 numbers++ 这样的复合操作仍然需要通过同步机制来保证操作的正确性。
 */
class MyData {

    volatile int numbers = 0;

    AtomicInteger atomicInteger = new AtomicInteger(0);


    public void addNumbers() {
        numbers++;
    }

    public void addAtomicInteger() {
        atomicInteger.incrementAndGet();
    }

}


public class VolatileTest {

    public static void main(String[] args) {
        MyData myData = new MyData();
        for (int i = 0; i < 20; i++) {
            new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    myData.addNumbers();
                    myData.addAtomicInteger();
                }
            }, String.valueOf(i)).start();
        }
        System.out.println("numbers 数据总和是" + myData.numbers);
        System.out.println("numbers 数据总和是" + myData.atomicInteger.getAndIncrement());

    }
}
