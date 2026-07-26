package com.axon.java.stack.juc.atomic;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicMarkableReference;

/**
 *
 * 一个引用 + 一个 boolean 标记 的原子配对。CAS 时同时检查"引用没变"和"标记没变"，两个都匹配才更新。
 *
 */
public class AtomicMarkableReferenceDemo {


    static AtomicMarkableReference atomicMarkableReference = new AtomicMarkableReference(100, false);

    public static void main(String[] args) {

        CyclicBarrier cycleBarrier = new CyclicBarrier(2);

        new Thread(() -> {

            boolean marked = atomicMarkableReference.isMarked();
            System.out.println(Thread.currentThread().getName() + "获取的marke默认值是" + marked);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            atomicMarkableReference.compareAndSet(100, 200, marked, !marked);
            System.out.println(Thread.currentThread().getName() + "当前获取的值是" + atomicMarkableReference.getReference());
            try {
                cycleBarrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() -> {
            boolean marked = atomicMarkableReference.isMarked();
            System.out.println(Thread.currentThread().getName() + "获取的marke默认值是" + marked);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            atomicMarkableReference.compareAndSet(100, 300, marked, !marked);
            System.out.println(Thread.currentThread().getName() + "当前获取的值是" + atomicMarkableReference.getReference());

            try {
                cycleBarrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }
        }).start();

        System.out.println("运行结束");
    }
}
