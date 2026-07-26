package com.axon.java.stack.juc.list;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;

/**
 * 在java中， arraryList、 HashSet、 HasMap都是线程不安全，那如何解决线程安全呢
 * <p>
 * 1.原因: 并发修改导致的，一个线程正在写入，另一个线程过来抢夺，导致数据不一致异常，并发修改异常
 * <p>
 * <p>
 * 2.解决方案
 *  ArraryList:
 *         //List<String> result = new ArrayList<>();  //线程不安全 ,报错 ：Exception in thread "9" Exception in thread "0" Exception in thread "3" java.util.ConcurrentModificationException
 *         //List<String> result = new Vector<>();  // 底层添加使用了synchronized 同步锁
 *         //List<String> result = Collections.synchronizedList(new ArrayList());  // 底层添加使用了synchronized 同步锁
 *         List<String> result = new CopyOnWriteArrayList<>(); //  底层源代码采取的了读写分离的策略。 每次操作之前先拷贝一份出来做修改。 修改完之后再添加到原来的集合中去， 这块代码也使用RentLock()
 *
 * HashSet:
 *         //HashSet<String> result = new HashSet<>();  // 线程不安全的， HashSet 底层使用的是HashMap,  添加进去的元素则对应的是HashMap中的Key, 而value 则使用的是一个常量
 *         //Set<String> result = Collections.synchronizedSet(new HashSet<>());  //底层添加使用了synchronized
 *         Set<String> result = new CopyOnWriteArraySet();   //  底层源代码采取的了读写分离的策略。 每次操作之前先拷贝一份出来做修改。 修改完之后再添加到原来的集合中去， 这块代码也使用RentLock()
 *
 * HashMap:
 *       // Map<String, String> result = new HashMap<>(); //  hashMap 和ConcurrentHashMap 的区别在哪里？
 *       // Map<String, String> result = new ConcurrentHashMap<>();
 *
 * <p>
 * <p>
 * 3.HashSet的使用场景，以及底层使用的是什么
 */
public class ListTest {

    public static void main(String[] args) throws InterruptedException {
        //arrayListTest();
        //testHashSet();
        hashMapTest();

    }

    private static void hashMapTest() throws InterruptedException {
        /**
         * HashMap 和 ConcurrentHashMap.txt 是 Java 中的两种常用的映射集合，它们在设计和使用场景上有一些显著的区别，特别是在并发编程中。
         *
         * 主要区别
         *
         * 	1.	线程安全性：
         * 	•	HashMap：非线程安全。在多线程环境中使用时可能会引发数据不一致的问题。例如，在扩容时可能出现死循环，导致程序挂起。
         * 	•	ConcurrentHashMap：线程安全。它通过多种机制（如分段锁、CAS操作等）保证在多线程环境下的安全操作。
         * 	2.	性能：
         * 	•	HashMap：由于不考虑线程安全性，通常在单线程环境下性能较好。
         * 	•	ConcurrentHashMap：由于引入了锁和同步机制，单线程性能略逊于 HashMap，但在多线程环境下，ConcurrentHashMap 的设计确保了高并发访问的效率。
         * 	3.	锁的粒度：
         * 	•	HashMap：没有锁机制，不支持线程安全。
         * 	•	ConcurrentHashMap：JDK 8 之前使用分段锁（Segment）来控制并发访问，每个分段包含一个独立的哈希桶。JDK 8 之后采用了一种更细粒度的锁机制，即基于 CAS 操作和 Synchronized 结合的方式，对每个桶（Node）进行控制，减少锁竞争。
         * 	4.	结构与实现：
         * 	•	HashMap：由数组和链表（JDK 1.8 及以上版本中的红黑树）组成。采用拉链法处理哈希冲突。
         * 	•	ConcurrentHashMap：JDK 8 之前的实现是基于分段锁结构，每个 Segment 包含一个哈希桶数组。JDK 8 之后取消了 Segment，直接采用数组和链表/红黑树的结构，并使用 CAS 和 Synchronized 保证线程安全。
         * 	5.	Null Key 和 Null Value：
         * 	•	HashMap：允许一个 null 键和多个 null 值。
         * 	•	ConcurrentHashMap：不允许 null 键和 null 值，原因是为了避免 null 导致的歧义和潜在的空指针异常。
         *
         * 底层原理
         *
         * HashMap 底层原理
         *
         * 	1.	数据结构：数组 + 链表/红黑树
         * 	2.	哈希冲突解决：采用拉链法，即相同哈希值的元素存储在同一个链表/红黑树中。
         * 	3.	扩容：当数组中的元素超过一定比例（负载因子，默认 0.75）时，会进行数组扩容，将现有元素重新散列到新的更大的数组中。
         * 	4.  当某个桶中链表长度达到 8 以上，且数组长度达到 64，转为红黑树。数组长度不足 64 则扩容。
         *
         * ConcurrentHashMap 底层原理
         *
         * 	•	JDK 7 及之前：
         * 	•	数据结构：Segment + 哈希桶数组 + 链表
         * 	•	分段锁：整个 ConcurrentHashMap 由多个 Segment 组成，每个 Segment 维护自己的哈希桶数组和链表。不同 Segment 可以并发访问，Segment 内使用锁机制保证线程安全。
         * 	•	JDK 8 及之后：
         * 	•	数据结构：哈希桶数组 + 链表/红黑树
         * 	•	CAS 和 Synchronized：通过 CAS 操作保证并发安全，链表转红黑树时使用 Synchronized 进行加锁。扩容时也使用类似的机制，减少对整个表的锁定范围。
         *
         * 	JDK 7： 默认 16 个 Segment，每个是 ReentrantLock + 小 HashMap 的合体。Segment 内 ReentrantLock 保护，Segment 间并发。锁粒度到段。
         *
         *  JDK 8： 就一个 Node 数组。空桶 CAS 插入无锁，有数据 synchronized 锁桶头。锁粒度到桶。
         *
         * 适用场景
         *
         * 	•	HashMap：适用于单线程环境，或者线程安全不是问题的场景。
         * 	•	ConcurrentHashMap：适用于多线程并发访问的场景，需要保证数据一致性和高效访问。
         */

        CountDownLatch latch = new CountDownLatch(10);
        // Map<String, String> result = new HashMap<>(); //  hashMap 和ConcurrentHashMap 的区别在哪里？
        Map<String, String> result = new ConcurrentHashMap<>();
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                result.put(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID().toString());
                System.out.println(result);
                latch.countDown();
            }, String.valueOf(i)).start();
        }
        latch.await();
        //可以添加相同的元素， 但是底层去重了
        System.out.println("运行结束");
    }

    private static void testHashSet() throws InterruptedException {
        /**
         * HashSet 使用场景
         *
         * 	1.	去重：HashSet 不允许存储重复的元素，这使得它非常适合用来去重。例如，从一组数据中提取唯一的元素。
         * 	2.	快速查找：HashSet 的查找操作平均时间复杂度为 O(1)，非常适合需要频繁查找元素的场景。
         * 	3.	无序集合：如果不关心元素的顺序，仅需要一个不重复的集合，可以使用 HashSet。
         * 	4.	集合运算：例如，集合的交集、并集、差集等操作。
         */
        CountDownLatch latch = new CountDownLatch(10);
        //HashSet<String> result = new HashSet<>();  // 线程不安全的， HashSet 底层使用的是HashMap,  添加进去的元素则对应的是HashMap中的Key, 而value 则使用的是一个常量
        //Set<String> result = Collections.synchronizedSet(new HashSet<>());  //底层添加使用了synchronized
        Set<String> result = new CopyOnWriteArraySet();   //  底层源代码采取的了读写分离的策略。 每次操作之前先拷贝一份出来做修改。 修改完之后再添加到原来的集合中去， 这块代码也使用RentLock()
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                result.add(UUID.randomUUID().toString().substring(0, 8));
                System.out.println(result);
                latch.countDown();
            }, String.valueOf(i)).start();
        }
        result.add("11");
        result.add("11");
        latch.await();
        //可以添加相同的元素， 但是底层去重了
        System.out.println(result);

    }

    private static void arrayListTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(10);
        //1.ArrayList 的解决方案
        //List<String> result = new ArrayList<>();  //线程不安全 ,报错 ：Exception in thread "9" Exception in thread "0" Exception in thread "3" java.util.ConcurrentModificationException
        //List<String> result = new Vector<>();  // 底层添加使用了synchronized 同步锁
        //List<String> result = Collections.synchronizedList(new ArrayList());  // 底层添加使用了synchronized 同步锁
        List<String> result = new CopyOnWriteArrayList<>(); //  底层源代码采取的了读写分离的策略。 每次操作之前先拷贝一份出来做修改。 修改完之后再添加到原来的集合中去， 这块代码也使用RentLock()

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                result.add(UUID.randomUUID().toString().substring(0, 8));
                System.out.println(result);
                latch.countDown();
            }, String.valueOf(i)).start();
        }
        latch.await();
        System.out.println("执行结束");
    }
}
