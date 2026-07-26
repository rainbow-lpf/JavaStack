package com.axon.java.stack.juc.lockUpgrade;


import org.openjdk.jol.info.ClassLayout;

/**
 * 锁升级：
 * 无锁-》偏向锁(场景：长时间去同一家饭店，老板认识你了，点菜老样子。)-》轻量级锁(CAS)-》重量级锁
 * <p>
 * *  Monitor是JVM底层的熟悉的，底层代码是C++,本质是依赖于底层操作系统的Mutex Lock实现，操作系统实现线程之间的切换需要从用户态到内核态的转换，
 * *  这种转换需要耗费很多的处理器时间成本非常高。 所以synchronized是java 语言中一个重量级级别的操作。
 * <p>
 * 1.Monitor监视器锁与java对象以及线程是如何关联的？
 * 1>如果一个Java对象呗某个线程锁住，则该java的对象的mark word字段中的 LockWord会指向monitor的起始地址
 * 2>monitor的owner字段会存放拥有相关联对象锁的线程id.
 * <p>
 * 偏向锁： markWord 存储的是线程的偏向id
 * 轻量级锁：markWord 存储的是线程栈中 lock Record的指针。
 * 重量锁： markWord存储的是指向对中的monitor对象的指针。
 * <p>
 * 01: 无锁   101：偏向锁： 00: 轻量级锁   10：重量级别锁    11：垃圾回收标志
 *
 *  查看JVM的启动参数配置：
 *      java -XX:+PrintFlagsFinal -version | grep BiasedLocking
 *
 *
 *java.lang.Object object internals:
 *  OFFSET  SIZE   TYPE DESCRIPTION                               VALUE
 *       0     4        (object header)                           05 b0 80 16 (00000101(主要看这8位，判断是什么锁) 10110000 10000000 00010110) (377532421)
 *       4     4        (object header)                           8b 7f 00 00 (10001011 01111111 00000000 00000000) (32651)
 *       8     4        (object header)                           e5 01 00 f8 (11100101 00000001 00000000 11111000) (-134217243)
 *      12     4        (loss due to the next object alignment)
 * Instance size: 16 bytes
 * Space losses: 0 bytes internal + 4 bytes external = 4 bytes total
 *
 *
 *
 * 锁的演变顺序：
 *
 * 	1.	无锁（No Lock）：默认状态，没有任何锁机制。
 * 	2.	偏向锁（Biased Locking）：适用于一个线程长期独占对象的情况。偏向锁通过在对象的mark word中存储线程ID来实现。这样，如果同一个线程再次尝试获得锁，就不需要进行同步操作。
 * 	3.	轻量级锁（Lightweight Locking）：当偏向锁被撤销，或者多个线程争用锁时，偏向锁会升级为轻量级锁。轻量级锁使用CAS（比较和交换）机制来实现，锁的状态被保存在对象的mark word中，并使用一个锁记录（Lock Record）来进行管理。
 * 	4.	重量级锁（Heavyweight Locking）：当多个线程争用轻量级锁时，锁会升级为重量级锁。这种锁使用monitor来实现，涉及到线程的上下文切换，开销较大。
 *
 *
 * 	 *
 *  *  chatGpt总结：
 *  *
 *  *
 *  *  锁升级流程：
 *  *
 *  * 场景：线程A、B、C、D四个线程竞争同一把锁。
 *  *
 *  * 	1.	线程A第一次获得锁（偏向锁的初始化）：
 *      * 	•	当A线程首次进入同步块，JVM 会将该对象的对象头中的 Mark Word 设置为偏向锁状态，并将偏向锁的线程ID设置为线程A的ID。
 *      * 	•	由于当前没有其他线程竞争，这是一种优化，用于减少不必要的CAS操作。
 *  * 	2.	线程B尝试获取锁：
 *      * 	•	偏向锁撤销与CAS失败:
 *      * 	•	线程B尝试进入同步块时，发现该对象的偏向锁已经指向了线程A。
 *      * 	•	B线程会通过CAS操作尝试将对象头中的偏向锁线程ID更改为自己的线程ID。如果A线程仍然持有锁且没有释放，B线程的CAS操作会失败。
 *      * 	•	偏向锁撤销: 如果CAS操作失败，JVM 会撤销偏向锁，将其升级为轻量级锁。撤销偏向锁的过程包括暂停拥有偏向锁的线程（即A线程），检查其当前的执行状态，重新标记对象头并将锁标志位改为轻量级锁。
 *  * 	        B尝试获取偏向锁成功：
 *  * 	           	线程A已释放锁：
 *          * 	•	如果线程A已经执行完同步块并释放了锁，此时对象的Mark Word中依然保存着A线程的ID，表示当前对象依然是偏向锁状态。
 *          * 	•	线程B进入同步块：
 *          * 	•	线程B尝试获取锁时，发现对象头中的Mark Word为偏向锁，并且锁的持有者ID是线程A的ID。
 *          * 	•	CAS操作尝试获取偏向锁：
 *          * 	•	线程B会通过CAS操作尝试将对象头中的偏向锁ID从A线程的ID更改为自己的ID。此时，由于A已经释放了锁，B的CAS操作会成功。
 *          * 	•	成功获取锁：
 *          * 	•	当CAS操作成功后，Mark Word中的偏向锁ID被更新为B线程的ID，线程B获得了该对象的锁，进入同步块执行代码。
 *  * 	3.	轻量级锁竞争（线程B继续尝试）：
 *      * 	•	轻量级锁获取：
 *      * 	•	偏向锁撤销后，B线程再次尝试通过CAS操作获取轻量级锁。如果成功，B线程将成功获取锁并执行同步块。
 *      * 	•	此时，线程A会继续尝试通过CAS操作获取轻量级锁，未能获取锁的线程（如A）会进入自旋状态。
 *      * 	•	CAS自旋：
 *      * 	•	如果C和D线程也试图获取同一锁，它们将通过CAS操作进入自旋状态，尝试获取轻量级锁。
 *      * 	•	自旋策略：自旋的次数和策略会根据JVM的实现和硬件配置而有所不同。自旋次数过多会导致自旋失败，促使锁进一步升级。
 *  * 	4.	升级为重量级锁：
 *      * 	•	自旋失败与锁膨胀：
 *      * 	•	当线程C和D的自旋次数达到上限（在不同的JVM实现中，自旋次数的策略有所不同），自旋仍然失败时，JVM 会将轻量级锁升级为重量级锁。
 *      * 	•	重量级锁的标志是对象头中的 Mark Word 被更新为指向操作系统的互斥量（Monitor），并且所有未能获得锁的线程（如A、C、D）将被阻塞，直到锁被释放。
 *  *
 *  *
 *  *
 *  *
 *  * 	请说明，为什么哈希码和偏向锁不能同时存在，而和轻量级锁和重量级锁能同时存在？
 *  *
 *      * 1. 对象头（Mark Word）的结构：
 *      * 在JVM中，每个对象的对象头（Mark Word）用于存储与锁状态相关的信息。对象头的结构根据锁状态的不同而变化。Mark Word中的信息可能包括：
 *      *
 *      * 	•	偏向锁的线程ID
 *      * 	•	锁状态（无锁、偏向锁、轻量级锁、重量级锁）
 *      * 	•	对象的哈希码
 *      * 	•	GC标记信息
 *      *
 *      * 2. 偏向锁与哈希码的冲突：
 *      * 	•	偏向锁的设计：
 *      * 	•	偏向锁是为减少不必要的CAS操作而设计的优化措施，它会将对象头中的一部分用来存储持有偏向锁的线程ID。当一个线程首次获取对象锁时，如果对象处于无锁状态，偏向锁会将对象头的部分空间用于记录这个线程的ID，以后该线程再次进入同步块时，无需再进行CAS操作即可确认锁的持有权。
 *      * 	•	哈希码计算与存储：
 *      * 	•	对象的哈希码通常是在第一次调用hashCode()方法时计算的。计算出的哈希码需要存储在对象头的Mark Word中。然而，如果对象已经处于偏向锁状态，Mark Word中已经被用于存储偏向锁的线程ID，因此无法再同时存储哈希码。
 *      * 	•	两者冲突的解决：
 *      * 	•	如果在对象已经偏向某个线程之后，需要计算并存储哈希码，则JVM会撤销偏向锁，将锁升级为轻量级锁或重量级锁。这时，Mark Word可以腾出空间来存储哈希码，因为轻量级锁和重量级锁的实现允许Mark Word中同时存储锁信息和哈希码。
 *      *
 *      * 3. 轻量级锁与重量级锁的共存性：
 *      * 	•	轻量级锁：
 *      * 	•	当偏向锁被撤销或在竞争激烈时，锁可能会升级为轻量级锁。轻量级锁将锁的信息存储在锁记录（LockRecord）中，而对象头中的Mark Word可以用来存储哈希码。
 *      * 	•	重量级锁：
 *      * 	•	当锁的竞争更加激烈，锁会升级为重量级锁，此时对象头的Mark Word会指向重量级锁的Monitor对象，Monitor对象中有更多的空间来存储锁相关的信息。而对象头中的Mark Word依然可以同时存储哈希码。
 *      *
 *      * 总结：
 *      * 	•	偏向锁：偏向锁需要在Mark Word中存储线程ID，但Mark Word的空间有限，无法同时存储哈希码。一旦需要计算哈希码，偏向锁会被撤销。
 *      * 	•	轻量级锁和重量级锁：这些锁不依赖Mark Word存储线程ID，而是使用其他结构（如锁记录或Monitor对象）来管理锁的信息，因此Mark Word可以同时存储哈希码和锁信息。
 *
 *
 *
 *      为什么轻量级锁和重量级锁可以存储哈希码？
 * 	    •	在偏向锁升级为轻量级锁或重量级锁后，JVM会通过不同的方式处理哈希码的存储，以确保哈希码不会丢失：
 * 	    •	轻量级锁：哈希码被存储在Lock Record中，Mark Word中不再直接存储哈希码。
 * 	    •	重量级锁：哈希码被迁移到Monitor对象中，Mark Word存储指向Monitor对象的指针。
 *  *
 *  *
 *
 *  假设线程A、B 同时争抢锁 。
 *
 *  无锁 → 偏向锁： A 第一次来，在对象头刻自己的线程 ID，以后再进直接比对 ID，CAS 都不用。
 *
 *  偏向锁 → 轻量级锁： B 来了 CAS 改 ID 失败（A 还持有），JVM 撤销偏向锁，升级轻量级锁。B 自旋等 A 释放，不阻塞。
 *
 *  轻量级锁 → 重量级锁： B 自旋超过阈值 A 还不放，JVM 转交 OS 管——ObjectMonitor，B 挂起阻塞，等 A 释放再唤醒。
 *
 * 核心思想： 锁逐级升级，不是一步到位。大多数情况下锁没竞争或持有时间极短，偏向锁和轻量级锁就够了，免去 OS 态切换开销。
 *
 *
 */

public class lockUpgrade {

    private static Object object = new Object();

    public static void main(String[] args) throws InterruptedException {

        System.out.println("锁执之前的信息：");
        System.out.println(ClassLayout.parseInstance(object).toPrintable());

        ///JVM 启动后，需要等待4s才能启动偏向锁，目前走的是轻量级锁
      //  Thread.sleep(6000);   // 停顿5s，可使用偏量锁  也可以设置命令来启用偏量锁：-XX:+UseBiasedLocking -XX:BiasedLockingStartupDelay=0

        synchronized (object) {
            System.out.println("锁之后的信息：");
            System.out.println(ClassLayout.parseInstance(object).toPrintable());
            System.out.println("锁块");
        }
        System.out.println("释放锁之后的信息：");
        System.out.println(ClassLayout.parseInstance(object).toPrintable());


    }
}
