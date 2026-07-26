package com.axon.java.stack.juc.sync;


/**
 *  在java中为什么任何一个对象都可以成为锁？
 *      1.在java中每一个对象都继承Object.
 *      2.Object 则对应的Jvm中的源码ObjectMonitor.cpp->ObjectMonitor.hpp
 *      3.在ObjectMonitor中拥有以下属性
 *          _owner: 指向持有 ObjectMonitor 的线程
 *          _count: 用于记录该线程获取锁的次数
 *          _recursions: 用于记录锁的重入次数
 *          _EntryList: 存放处于等待锁block状态的线程队列
 *          _WaitSet:  存放处于等待锁wait状态的线程队列
 *
 * chatgpt总结：
 *  1.在Java中，每个对象都继承自Object类:
 *      每个Java对象都继承自Object类：这意味着每个Java对象都具有一些基础功能，这些功能在Object类中定义，例如hashCode()、equals()、wait()、notify()、notifyAll()等。
 * 	•	每个Java对象都有一个隐含的监视器锁（Monitor），这是Java同步机制的核心。
 * 	2.在JVM中，监视器锁的实现:
 * 	•	ObjectMonitor是JVM内部用于实现对象锁的关键数据结构。这个类位于ObjectMonitor.cpp文件中，其定义在ObjectMonitor.hpp文件中。
 * 	3.ObjectMonitor的关键属性:
 * 	•	_owner: 指向当前持有该ObjectMonitor的线程。这意味着该线程正在执行同步代码。
 * 	•	_count: 用于记录持有锁的次数，通常用于实现可重入锁机制。即同一线程可以多次进入同步代码块，而不会死锁。
 * 	•	_recursions: 用于记录锁的重入次数，这个属性结合_count一起工作，用于支持重入锁。
 * 	•	_EntryList: 存放处于“阻塞”状态的线程队列，这些线程在等待锁的释放。
 * 	•	_WaitSet: 存放处于“等待”状态的线程队列，这些线程通常在调用Object.wait()方法后进入此队列，等待被唤醒。
 * 	4.每个对象都能成为锁的原因:
 * 	•   因为Java中的每个对象都与一个隐含的监视器锁相关联，这使得每个对象都可以用作同步代码块中的锁。
 *
 *
 *
 *
 * 	普通对象 Person { name, age }
 *         │
 *         │ synchronized(person) 升级为重量级锁时
 *         ▼
 *    ObjectMonitor（只管锁，不管 name/age）
 *    ┌─────────────────────┐
 *    │ _owner    = 线程A   │ ← 当前谁持锁
 *    │ _count    = 1       │ ← 重入次数
 *    │ _EntryList = [B, C] │ ← BLOCKED 排队
 *    │ _WaitSet  = [D]     │ ← 调了 wait() 等着
 *    └─────────────────────┘
 *
 *
 * 在java中为什么任何一个对象都可以成为锁？
    对象由对象头、实例数据、对齐填充构成。对象头中的 Mark Word 存了 hashcode、分代年龄、锁标记位、偏向锁持有者线程 ID。
    锁升级从无锁 → 偏向锁（存线程 ID）→ 轻量级锁（存栈指针）→ 重量级锁（存 ObjectMonitor 指针）。ObjectMonitor 是 JVM 的 C++ 对象，内部维护 _owner、_EntryList、_WaitSet 等。
    Mark Word 的多态性让它既可以存偏向线程 ID，也可以存 ObjectMonitor 地址。正因为对象天生带有这个锁标记位，所以 Java 里任何对象都能当锁。
 */

public class SyncSummaryTest {

    public static void main(String[] args) {

    }
}
