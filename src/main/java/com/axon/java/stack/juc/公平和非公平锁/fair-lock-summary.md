# ReentrantLock & 读写锁 & Condition — 面试总结

> 目录：`com.axon.java.stack.juc.公平和非公平锁`

---

## 一、可重入锁（递归锁）

### 案例：`ReentrantLockTestDemo.java`

**定义：同一个线程可以多次获取同一把锁，不会死锁。**

```java
// synchronized — 隐式可重入
class SynchronizedTest {
    public void test1() {
        synchronized (object) {
            synchronized (object) {   // 同一线程再拿同一把锁，ok
                test2();
            }
        }
    }
    public void test2() {
        synchronized (object) { }    // 第三次拿，还是 ok
    }
}

// ReentrantLock — 显式可重入
class ReentrantLockTest {
    Lock lock = new ReentrantLock();
    public void test1() {
        lock.lock();
        lock.lock();    // 同一线程再拿，计数器+1
        try {
            test2();
        } finally {
            lock.unlock();  // 计数器-1
            lock.unlock();  // 计数器归零，真正释放
        }
    }
}
```

**原理：** 内部有一个 `_count`/`state` 计数器。同一线程每次 `lock` +1，每次 `unlock` -1，归零才真正释放锁。

| | synchronized | ReentrantLock |
|---|---|---|
| 重入方式 | 隐式（内置），自动 | 显式，state 计数 |
| 释放 | 自动 | 手动 unlock，**必须配对** |
| unlock 次数 > lock | 不会发生 | 抛 `IllegalMonitorStateException` |

---

## 二、公平锁 vs 非公平锁

### 案例：`FairAndNotFairLockDemoTest.java`

```java
// 公平锁：按排队顺序获取
ReentrantLock lock = new ReentrantLock(true);

// 非公平锁：允许插队（默认）
ReentrantLock lock = new ReentrantLock(false);  // 或 new ReentrantLock();
```

| | 公平锁 | 非公平锁 |
|---|---|---|
| 获取顺序 | 先来后到，FIFO | 允许插队 |
| 性能 | **较低**（频繁线程切换） | **较高**（减少上下文切换） |
| 饥饿风险 | ❌ 无 | ⚠️ 可能某些线程永远抢不到 |
| 实现 | 先检查队列有没有人等 | 直接 CAS 抢锁 |
| 默认 | — | ✅ ReentrantLock 默认非公平 |

### 为什么非公平性能高？

```
非公平锁：
线程A 刚释放锁 → 线程A 立刻再抢 → 大概率抢到
→ 线程A 继续处理 → 少一次线程切换

公平锁：
线程A 释放锁 → 检查队列 → 队头线程B → 切换线程B
→ 上下文切换 → 慢
```

---

## 三、读写锁（ReentrantReadWriteLock）

### 案例：`ReadWriterLockTest.java`

```java
ReadWriteLock lock = new ReentrantReadWriteLock();

// 读锁 — 共享锁
lock.readLock().lock();
try { map.get(key); } finally { lock.readLock().unlock(); }

// 写锁 — 独占锁
lock.writeLock().lock();
try { map.put(key, value); } finally { lock.writeLock().unlock(); }
```

### 共存规则

| | 读锁 | 写锁 |
|---|---|---|
| **读锁** | ✅ 可共存（读读共享） | ❌ 互斥 |
| **写锁** | ❌ 互斥 | ❌ 互斥（写写互斥） |

```
读锁 + 读锁 ：兼容，同时并发读
读锁 + 写锁 ：互斥，有一个读就不能写
写锁 + 写锁 ：互斥，只能一个人写
写锁 + 读锁 ：互斥，有一个写就不能读
```

### 使用场景

> **读多写少**的场景——缓存、配置文件。读完全不互斥，并发度远超 `synchronized`。

### 锁降级

写锁可以降级为读锁，反之不行：

```java
writeLock.lock();
try {
    map.put(key, value);
    readLock.lock();     // 获取读锁
} finally {
    writeLock.unlock();  // 释放写锁 → 降级为读锁
}
try {
    // 此时持有读锁
} finally {
    readLock.unlock();
}
```

---

## 四、Condition — 精确唤醒

### 案例：`ReentrantLockConditionTest.java`

**synchronized 只能 `notifyAll` 唤醒所有线程。Condition 可以精确唤醒指定线程。**

```java
Lock lock = new ReentrantLock();
Condition c1 = lock.newCondition();  // C1 的条件
Condition c2 = lock.newCondition();  // C2 的条件
Condition c3 = lock.newCondition();  // C3 的条件
int number = 0;  // 调度标记

// C1：number=0 时打印5次，然后设 number=1，唤醒 C2
public void print5() {
    lock.lock();
    try {
        while (number != 0) { c1.await(); }  // 不是0就等
        for (int i = 0; i < 5; i++) { ... }
        number = 1;
        c2.signal();   // 精确唤醒 C2！
    } finally { lock.unlock(); }
}

// C2：number=1 时打印10次，然后设 number=2，唤醒 C3
// C3：number=2 时打印15次，然后设 number=0，唤醒 C1
```

**流程：** C1(5次) → C2(10次) → C3(15次) → C1(5次) → ... 精确循环。

---

### Condition vs Object 等待/通知

| | Object | Condition |
|---|---|---|
| 配合锁 | `synchronized` | `ReentrantLock` |
| 等待 | `wait()` | `await()` |
| 唤醒一个 | `notify()` | `signal()` |
| 唤醒全部 | `notifyAll()` | `signalAll()` |
| 精确唤醒 | ❌ 不支持 | ✅ 多个 Condition 分别控制 |

---

### signal() 关键细节

> **signal() 不会立即释放锁！** 只是把等待线程从等待队列移到同步队列。当前线程 `unlock()` 后，被唤醒的线程才能拿锁。

```java
lock.lock();
try {
    c2.signal();   // C2 被移到同步队列，但锁还在这里
    // C2 现在拿不到锁，等当前线程 unlock
} finally {
    lock.unlock();  // C2 现在可以去抢锁了
}
```

### 为什么用 `while` 而不是 `if`？

```java
// ❌ if — 虚假唤醒后直接往下走，可能条件不对
if (number != 0) { c1.await(); }

// ✅ while — 被唤醒后重新检查条件
while (number != 0) { c1.await(); }
```

> 防止**虚假唤醒**——线程被唤醒不代表条件满足，要用 while 重新判断。

---

## 五、面试话术

### 可重入锁（15 秒版）

> 同一个线程可以多次获取同一把锁。synchronized 隐式支持，ReentrantLock 用 state 计数器实现。lock 和 unlock 必须配对，否则抛异常或死锁。

### 公平锁 vs 非公平锁（20 秒版）

> 公平锁按排队顺序获取，避免饥饿，但线程切换多、性能低。非公平锁允许插队，刚释放的线程容易再次抢到，减少上下文切换，性能高。ReentrantLock 默认非公平。

### 读写锁（20 秒版）

> ReadWriteLock：读读共享、读写互斥、写写互斥。适合读多写少场景，读并发度远高于普通锁。写锁可降级为读锁，反之不行。

### Condition（20 秒版）

> synchronized 只能 notifyAll 全唤醒，Condition 可以多个条件队列，精准唤醒指定线程。signal() 不释放锁，只是把线程移到同步队列。用 while 防虚假唤醒。
