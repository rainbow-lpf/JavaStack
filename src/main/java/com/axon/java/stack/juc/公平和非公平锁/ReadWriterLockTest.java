package com.axon.java.stack.juc.公平和非公平锁;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 读写锁的使用：
 * <p>
 * 独占锁： 适用于写锁
 * 共享锁： 适用于读锁
 *
 * 读读可以共存
 * 读写 不能共存
 * 写写 不能共存
 *
 * 总结和注意事项
 *
 * 	1.	独占锁和共享锁：
 * 	•	写操作需要独占锁（写锁），读操作可以使用共享锁（读锁）。
 * 	•	读操作和读操作之间可以共享锁，而写操作与读/写操作不能同时存在。
 * 	2.	实际场景中的应用：
 * 	•	读写锁适用于读操作远多于写操作的场景，可以提高并发度。
 * 	3.	代码的注意事项：
 * 	•	在使用 ReentrantReadWriteLock 时，确保 lock() 和 unlock() 成对出现，以避免死锁等问题。
 * 	•	在 try 块中处理逻辑，finally 块中释放锁，以确保即使发生异常也能正确释放锁。
 *
 * 修改后的代码中，确保了锁的正确使用，并分别演示了不使用锁和使用读写锁的场景。
 *
 *
 */

class NormalReadWriterTest {

    private volatile Map<String, String> hashMap = new HashMap<>();

    private ReadWriteLock lock = new ReentrantReadWriteLock();

    public void put(String key, String value) {
        System.out.println(Thread.currentThread().getName() + "正在写入" + key);
        try {
            hashMap.put(key, value);
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println(Thread.currentThread().getName() + "写入结束");
    }

    public void get(String key) {
        System.out.println(Thread.currentThread().getName() + "正在读取" + key);
        try {
            String integer = hashMap.get(key);
            // System.out.println("当前结果值:" + integer);
           // Thread.sleep(300);
            System.out.println(Thread.currentThread().getName() + "读取完成，结果" + integer);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void putLock(String key, String value) {
        lock.writeLock().lock();
        try {
            System.out.println(Thread.currentThread().getName() + "正在写入" + key);
            try {
                hashMap.put(key, value);
                Thread.sleep(300);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + "写入结束");
        } catch (Exception exception) {

        } finally {
            lock.writeLock().unlock();
        }

    }

    public void getLock(String key) {
        lock.readLock().lock();

        try {
            System.out.println(Thread.currentThread().getName() + "正在读取" + key);
            try {
                String integer = hashMap.get(key);
                // System.out.println("当前结果值:" + integer);
                Thread.sleep(300);
                System.out.println(Thread.currentThread().getName() + "读取完成，结果" + integer);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            lock.readLock().unlock();

        }
    }


}


public class ReadWriterLockTest {

    public static void main(String[] args) throws InterruptedException {
        normalTest();  // 无锁测试

        System.out.println("开始枷锁测试。。。。。。。。。。。");
        readWirteLockTest(); // 枷锁测试

        System.out.println(" 执行结束");
    }

    private static void normalTest() throws InterruptedException {
        NormalReadWriterTest test = new NormalReadWriterTest();
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            new Thread(() -> {

                test.put(String.valueOf(finalI), String.valueOf(finalI));
            }).start();
        }


        for (int i = 0; i < 5; i++) {
            int finalI = i;
            new Thread(() -> {
                test.get(String.valueOf(finalI));
            }).start();
        }


        Thread.sleep(2000);
    }

    private static void readWirteLockTest() throws InterruptedException {
        NormalReadWriterTest test = new NormalReadWriterTest();
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            new Thread(() -> {

                test.putLock(String.valueOf(finalI), String.valueOf(finalI));
            }).start();
        }


        for (int i = 0; i < 5; i++) {
            int finalI = i;
            new Thread(() -> {
                test.getLock(String.valueOf(finalI));
            }).start();
        }


        Thread.sleep(2000);
    }
}
