package com.axon.java.stack.juc.公平和非公平锁;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可重入锁又名（递归锁）
 *  即允许同一个线程多次获取同一把锁。这个特性使得在同一个线程中调用其它被锁保护的方法时不会导致死锁
 * <p>
 * 常见的锁有： ReentrantLock 、  synchronized
 * <p>
 * <p>
 * synchronized: 隐式的可重入锁， 递归锁）
 * ReentrantLock： 显示的可重入锁 , lock 和 unlock 必须成双成对出现
 *
 *总结
 *
 * 	1.	可重入锁的概念：
 * 	•	可重入锁（又称递归锁）允许同一个线程多次获取同一把锁。这个特性使得在同一个线程中调用其它被锁保护的方法时不会导致死锁。
 * 	2.	实现方式：
 * 	•	synchronized：Java 中的内置锁，是隐式的可重入锁。使用时，不需要显式地释放锁，当方法或代码块执行完毕时，锁会自动释放。
 * 	•	ReentrantLock：Java java.util.concurrent.locks 包中的类，是显式的可重入锁。使用时，必须显式地调用 lock() 获取锁，并在最终块中调用 unlock() 释放锁。ReentrantLock 提供了更灵活的锁操作，如可中断锁申请、定时锁申请等。
 * 	3.	案例演示：
 * 	•	代码中的 SynchronizedTest 类演示了 synchronized 关键字的使用，ReentrantLockTest 类则演示了 ReentrantLock 的使用。两者都展示了如何在同一个线程内重复获取锁并调用其它被锁保护的方法。
 *
 * 代码分析和修改
 *
 * 	1.	SynchronizedTest 类：
 * 	•	该类使用 synchronized 关键字来确保线程安全。在 test1 方法中，一个嵌套的 synchronized 块被锁定，之后调用了 test2 方法，test2 方法中再次使用 synchronized 锁。这体现了 synchronized 作为隐式可重入锁的特性。
 * 	2.	ReentrantLockTest 类：
 * 	•	该类使用 ReentrantLock 来实现锁。test1 方法中使用 lock.lock() 两次来获取锁，这表明同一个线程可以多次获取同一把锁。需要注意的是，lock 和 unlock 必须成对出现，否则会导致死锁或其他线程无法获取锁。
 * 	3.	问题和修改：
 * 	•	ReentrantLockTest 类中的 lock 和 unlock 必须严格配对使用，如果 unlock 次数多于 lock，会抛出 IllegalMonitorStateException 异常。当前代码中没有发现明显问题，但应确保每次 lock 后必须有相应的 unlock。同时，在 ReentrantLockTest 类中，两个 lock.lock() 调用之间不应该调用其它方法以免破坏锁的结构。
 *
 *
 *
 */

class SynchronizedTest {
    private Object object = new Object();

    public void test1() {
        synchronized (object) {
            System.out.println("Synchronized的test1可重入1 进来了");
            synchronized (object) {
                System.out.println("Synchronized 的test1可重入2 进来了");
                test2();
            }
        }
    }

    public void test2() {
        synchronized (object) {
            System.out.println("Synchronized的test2的方可重入1 进来了");
            synchronized (object) {
                System.out.println("Synchronized的test2的方可重入2 进来了");

            }
        }
    }
}


class ReentrantLockTest {
    private Lock lock = new ReentrantLock();

    public void test1() {
        lock.lock();
        lock.lock();
        try {
            System.out.println("ReentrantLockTest的test1可重入1 进来了");
            test2();
        } catch (Exception exception) {
            System.out.println(exception.getMessage());
        } finally {
            lock.unlock();
            lock.unlock();
        }
    }

    public void test2() {
        lock.lock();
        try {
            System.out.println("ReentrantLockTest的test2可重入2 进来了");
        } catch (Exception exception) {

        } finally {
            lock.unlock();
        }
    }
}


public class ReentrantLockTestDemo {


    public static void main(String[] args) throws InterruptedException {
        SynchronizedTest test = new SynchronizedTest();

        new Thread(() -> test.test1(), "aaaa").start();

        new Thread(() -> test.test1(), "bbbb").start();


        ReentrantLockTest  reentrantLockTest=new ReentrantLockTest();
        new Thread(() -> reentrantLockTest.test1(), "aaaa").start();

        new Thread(() -> reentrantLockTest.test1(), "bbbb").start();

        Thread.sleep(3000);
        System.out.println("执行结束");
    }
}
