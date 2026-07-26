package com.axon.java.stack.juc.reference;

import java.lang.ref.WeakReference;

/**
 * 3. 弱引用（Weak Reference）
 * <p>
 * 定义：
 * <p>
 * 弱引用比软引用更弱。无论内存是否充足，垃圾回收器都会回收被弱引用指向的对象。
 * <p>
 * 使用场景：
 * <p>
 * 弱引用常用于 WeakHashMap 等场景中，键的生命周期不由显式管理，而由垃圾回收机制决定。
 *
 *
 *
 * ## Java 四种引用：强软弱虚
 *
 * ---
 *
 * ### 强引用（Strong Reference）
 *
 * ```java
 * Object obj = new Object();  // 99%的代码都是这个
 * ```
 *
 * **死也不回收。** 只要引用还在，OOM 也不回收。
 *
 * **使用场景：** 日常编程，所有 `new` 出来的对象默认就是强引用。
 *
 * ---
 *
 * ### 软引用（Soft Reference）
 *
 * ```java
 * SoftReference<byte[]> cache = new SoftReference<>(new byte[1024*1024]);  // 1MB
 * ```
 *
 * **OOM 前一刻回收。** 内存够就留着，内存不够 GC 就清掉。
 *
 * **使用场景：** 缓存——图片缓存、页面缓存。内存充足时快取，紧张时自动清退，不炸应用。
 *
 * ```java
 * byte[] data = cache.get();
 * if (data == null) {
 *     data = loadFromDisk();
 *     cache = new SoftReference<>(data);
 * }
 * ```
 *
 * ---
 *
 * ### 弱引用（Weak Reference）
 *
 * ```java
 * WeakReference<User> userRef = new WeakReference<>(new User());
 * ```
 *
 * **GC 一到立马回收，不等内存不够。**
 *
 * **使用场景：**
 * - **ThreadLocal：** `ThreadLocalMap` 的 Entry 的 key 就是弱引用（`WeakReference<ThreadLocal>`），防止 ThreadLocal 对象被外部忘记后无法回收。
 * - **WeakHashMap：** key 弱引用，外部没人引用了自动从 map 里消失。
 * - **避免内存泄漏：** 监听器注册、回调等——你忘了注销，GC 也帮你清。
 *
 * ```java
 * // ThreadLocal 本质
 * static class Entry extends WeakReference<ThreadLocal<?>> {
 *     Object value;
 * }
 * // 当 ThreadLocal 对象没强引用时，key 被 GC 清掉 → 防止泄漏
 * ```
 *
 * ---
 *
 * ### 虚引用（Phantom Reference）
 *
 * ```java
 * PhantomReference<byte[]> phantom = new PhantomReference<>(obj, queue);
 * // phantom.get() 永远返回 null —— 拿不到对象
 * ```
 *
 * **比弱引用还弱，连对象都摸不到，唯一作用是告诉你"它死了"。**
 *
 * **使用场景：** 对象回收后做善后——堆外内存释放（DirectByteBuffer）、大文件删除。
 *
 * ```java
 * // DirectByteBuffer 的回收机制
 * // 堆外内存不是 JVM 管的，GC 只能回收堆内的 DirectByteBuffer 对象
 * // 虚引用收到通知 → Cleaner 调用 unsafe.freeMemory() 释放堆外内存
 * ```
 *
 * ---
 *
 * ### 面试话术（30秒版）
 *
 * | 引用类型 | GC 策略 | 一句话 | 使用场景 |
 * |---------|--------|--------|---------|
 * | 强引用 | 永不回收 | "死了也不让你收" | 日常 new |
 * | 软引用 | OOM 前回收 | "内存够别动我，不够你再收" | 缓存 |
 * | 弱引用 | GC 即回收 | "GC 来了我马上走" | ThreadLocal、WeakHashMap |
 * | 虚引用 | 回收后通知 | "你死了通知我一声，好善后" | 堆外内存释放 |
 *
 * > **一句话总结：** 强不回收，软到 OOM，弱见 GC 走，虚只收尸。
 */
public class WeakReferenceExample {

    public static void main(String[] args) {
        // 创建一个弱引用对象
        WeakReference<Object> weakRef = new WeakReference<>(new Object());

        // 手动调用垃圾回收
        System.gc();

        // 弱引用对象可能会被回收
        if (weakRef.get() != null) {
            System.out.println("Weak reference is still alive.");
        } else {
            System.out.println("Weak reference has been collected.");
        }
    }
}
