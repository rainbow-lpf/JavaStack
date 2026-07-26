package com.axon.java.stack.juc.公平和非公平锁;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;

class MyFairAndNotFairLock {

    private ReentrantLock lock;
    private volatile int number = 50;

    public MyFairAndNotFairLock(boolean flag) {
        lock = new ReentrantLock(flag);
    }


    public void testRun() {
        lock.lock();
        try {
            if (number > 0) {
                System.out.println(Thread.currentThread().getName() + "当前线程卖票数量剩余" + number--);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

}


/**
 * 公平锁和非公平锁
 * <p>
 * 非公平锁： 性能高一点， 第一获取锁后，再次获取锁的概率极大，减少线程上下文的切换。
 * 公平锁：  性能相对非公平锁弱， 因为公平锁需要线程来回切换，增加线程上下文的切换
 * <p>
 * <p>
 * chatgpt的解释
 * <p>
 * 公平锁与非公平锁的解释：
 * <p>
 * •	公平锁：线程按照请求锁的顺序获取锁，避免饥饿现象，但由于线程的频繁切换，性能会稍低。
 * •	非公平锁：线程可以“插队”获取锁，性能更高，但可能导致某些线程长期得不到锁，产生饥饿现象。
 * <p>
 * •	在高并发环境下，如果要求性能，可以选择非公平锁；如果更注重线程的公平性，则可以选择公平锁。
 */
public class FairAndNotFairLockDemoTest {

    public static void main(String[] args) throws InterruptedException {

        MyFairAndNotFairLock lockTest = new MyFairAndNotFairLock(false);
        //演示案例
        CountDownLatch countDownLatch = new CountDownLatch(2);
        //演示案例
        CyclicBarrier cyclicBarrier = new CyclicBarrier(2);
        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                lockTest.testRun();
            }
            countDownLatch.countDown();
            try {
                cyclicBarrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }
        }, "AAAA").start();


        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                lockTest.testRun();
            }
            countDownLatch.countDown();
            try {
                cyclicBarrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }

        }, "BBB").start();
        countDownLatch.await();
        System.out.println("运行结束");
    }
}
