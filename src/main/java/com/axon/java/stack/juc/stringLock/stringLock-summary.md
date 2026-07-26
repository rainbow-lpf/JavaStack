# 字符串做锁 & 锁对象选择 — 面试总结

> 目录：`com.axon.java.stack.juc.stringLock`

---

## 一、字符串能当锁吗？

**能，但不推荐。** 原因是 **字符串常量池**——内容相同的字符串字面量指向同一个对象，导致无意中的锁共享。

---

## 二、为什么字符串会锁共享？

```java
String s1 = "lock";
String s2 = "lock";

synchronized (s1) { ... }   // 线程A 拿锁
synchronized (s2) { ... }   // 线程B 拿的是同一把锁！阻塞！
```

```
JVM 字符串常量池：
┌──────────────┐
│  "lock"      │  ← 只有一份
│  0x00001234  │
└──────────────┘
    ↑        ↑
   s1       s2       ← 两个变量指向同一个对象
```

**本质：** `"lock"` 在常量池只有一份。两个看起来不相干的变量，指向同一个对象，锁就串了一—竞争、阻塞甚至死锁。

---

## 三、`new Object()` 为什么安全？

```java
Object lock1 = new Object();    // 地址 0xAAAA
Object lock2 = new Object();    // 地址 0xBBBB

synchronized (lock1) { ... }    // 锁A
synchronized (lock2) { ... }    // 锁B  ← 互不影响
```

**每次 `new` 都在堆上创建新对象，地址不同，锁天然隔离。**

---

## 四、对比

| | `String` 做锁 | `new Object()` 做锁 |
|---|---|---|
| 对象来源 | 常量池，内容相同就同一个 | 堆，每次都 new 新的 |
| 锁隔离性 | ❌ 可能无意共享 | ✅ 天然隔离 |
| 风险 | 锁竞争、死锁、排查困难 | 无共享风险 |

---

## 五、String.intern() 也会踩坑

```java
String s3 = new StringBuilder("lock").toString().intern();  // 强制入池
String s4 = "lock";                                          // 常量池引用

// s3 == s4 → true，同一把锁！
```

---

## 六、面试回答

> **字符串不建议当锁。** 字符串常量池会让内容相同的字面量指向同一个对象，两个看起来不相干的 `synchronized` 块实际上抢同一把锁，导致无意的锁竞争甚至死锁。
>
> **用 `new Object()` 做锁，** 每次创建的都是独立对象，锁天然隔离，没有共享风险。
>
> **`synchronized` 锁的是对象，不是变量名。** 变量名不同但指向同一个对象，就是同一把锁。
