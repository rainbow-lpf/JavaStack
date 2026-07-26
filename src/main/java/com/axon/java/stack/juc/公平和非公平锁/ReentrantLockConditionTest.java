package com.axon.java.stack.juc.公平和非公平锁;


import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 测试 Condition 绑定多个线程，精确唤醒
 * <p>
 * 场景： C1 打印5个数之后通知C2打印10个数，C2打印完成之后通知C3 打印15个数，C3打印完成之后 通知C1 打印
 *
 * 	精确控制线程执行顺序：通过 number 变量和 Condition，实现了线程之间的精确唤醒和等待，确保线程按照预定的顺序执行打印任务。
 * 	2.	使用 ReentrantLock 和 Condition：使用 ReentrantLock 实现线程安全，并通过 Condition 实现更细粒度的线程协调。
 * 	3.	潜在的问题：
 * 	•	性能问题：每次都使用 signalAll，可能会唤醒不必要的线程，影响性能。可以考虑使用 signal 以提高效率。
 * 	•	线程数量和逻辑扩展：当前实现只适用于固定的三个线程和对应的任务。如果需要扩展到更多线程和任务，需要增加更多条件和相应的逻辑。
 *
 * 优化建议
 *
 * 	•	使用 signal 替代 signalAll：如果可以确定每次只需要唤醒一个线程，使用 signal 可以提高性能。
 * 	•	改进线程逻辑：考虑将线程执行逻辑抽象出来，以便于扩展和维护。
 *
 * 这段代码展示了如何使用 ReentrantLock 和 Condition 实现线程之间的精确控制和协调，适用于需要严格控制线程执行顺序的场景。
 *
 *
 * 在这段代码中，signal() 方法的确是在锁尚未释放的情况下被调用的，但这并不妨碍其设计的正确性。理解这一点需要深入了解 ReentrantLock 和 Condition 的工作机制。以下是详细分析：
 * 1. signal() 方法的作用
 * signal() 方法的作用是唤醒一个正在等待相应 Condition 的线程。如果没有线程在等待这个条件，那么 signal() 方法不起作用；如果有多个线程在等待，signal() 只会随机唤醒其中一个。被唤醒的线程将进入同步队列，等待获取锁。
 *
 * 2. 线程能否获得锁？
 * 在当前线程调用 signal() 之后，其他线程并不能立刻获得锁。确实，当前线程尚未调用 unlock()，所以锁仍然由当前线程持有。被 signal() 唤醒的线程会被移动到 ReentrantLock 的同步队列中，等待获取锁。
 *
 * 3. signal() 的实际意义
 * 尽管 signal() 方法是在锁未释放的情况下调用的，但其主要意义在于准备工作。具体来说，signal() 将唤醒一个线程，使其从等待状态变为就绪状态。就绪状态的线程会尝试获取锁，但由于锁仍然被持有，它将被阻塞在同步队列中，直到当前线程调用 unlock() 释放锁。
 * 一旦当前线程调用 unlock() 释放锁，唤醒的线程就可以竞争锁，并继续执行。如果它成功获取到锁，执行将继续下去。
 *
 * 总结：
 * signal() 的实际作用是将一个线程从等待队列中移动到就绪队列，但它无法立即使该线程获得锁。这个线程将继续被阻塞，直到持有锁的线程调用 unlock() 释放锁后，它才有机会继续执行。通过这种机制，代码实现了多线程之间的精确协调，确保它们按照指定的顺序执行。
 */
class Conditiontest {

    private Lock lock = new ReentrantLock();
    private Condition c1 = lock.newCondition();
    private Condition c2 = lock.newCondition();
    private Condition c3 = lock.newCondition();
    private volatile int number = 0;

    public void print5() {
        lock.lock();
        try {
            while (number != 0) {
                c1.await();
            }
            for (int i = 0; i < 5; i++) {
                System.out.println(Thread.currentThread().getName() + "打印" + i);
            }
            number = 1;
            c2.signal();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public void print10() {
        lock.lock();
        try {
            while (number != 1) {
                c2.await();
            }
            for (int i = 0; i < 10; i++) {
                System.out.println(Thread.currentThread().getName() + "打印" + i);
            }
            number = 2;
            c3.signal();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    public void print15() {
        lock.lock();
        try {
            while (number != 2) {
                c3.await();
            }
            for (int i = 0; i < 15; i++) {
                System.out.println(Thread.currentThread().getName() + "打印" + i);
            }
            number = 0;
            c1.signal();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }


}


public class ReentrantLockConditionTest {


    public static void main(String[] args) throws InterruptedException {

        Conditiontest conditiontest = new Conditiontest();

        new Thread(() -> {
            for (int i = 0; i <10 ; i++) {
                conditiontest.print5();

            }
        }, "C1").start();

        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                conditiontest.print10();
            }
        }, "C2").start();

        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                conditiontest.print15();

            }
        }, "C3").start();


        Thread.sleep(100000);

        System.out.println(" 结束");

    }
}
