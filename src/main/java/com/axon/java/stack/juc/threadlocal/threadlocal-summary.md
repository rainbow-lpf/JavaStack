# ThreadLocal & 四种引用 — 面试总结

> 目录：`com.axon.java.stack.juc.threadlocal`

---

## 一、ThreadLocal 是什么

**一句话：每个线程独享一份数据副本，线程间完全隔离，天然线程安全。**

```java
ThreadLocal<Integer> saleNums = ThreadLocal.withInitial(() -> 0);

// 线程1：set(1)  — 只在线程1的 Map 里
// 线程2：set(5)  — 只在线程2的 Map 里
// 互不影响
```

---

## 二、核心数据结构

```
Thread-1                          Thread-2
  │                                 │
  ├→ ThreadLocalMap                 ├→ ThreadLocalMap
  │   ┌──────────────────┐          │   ┌──────────────────┐
  │   │ key(弱)→ThreadLocal│         │   │ key(弱)→ThreadLocal│
  │   │ value(强)→ 数据    │         │   │ value(强)→ 数据    │
  │   └──────────────────┘          │   └──────────────────┘
          Entry ← 继承 WeakReference          Entry
```

| 组件 | 位置 | 说明 |
|------|------|------|
| `ThreadLocalMap` | 每个 `Thread` 对象里 | 线程私有，无锁访问 |
| Entry 的 key | `WeakReference<ThreadLocal>` | 弱引用，防泄漏 |
| Entry 的 value | Object | 强引用，实际的线程局部数据 |

> **比较：** ThreadLocal 不是共享数据（共享数据用 synchronized / Lock）。ThreadLocal 是**人各一份，各自拥有，互不干扰**。

---

## 三、ThreadLocal + 线程池 = 内存泄漏

### 为什么会泄漏？

```
1. ThreadLocal 外部强引用消失 → GC 回收 ThreadLocal 对象
2. ThreadLocalMap 中 key 变为 null（弱引用）
3. 但 value 是强引用 → 不会被回收
4. 线程池线程复用 → ThreadLocalMap 一直存活 → 无人可用的 value 一直占内存
```

```
外部 ThreadLocal  ──弱引用──→ [ThreadLocal对象]  ← GC 回收了
                                     ↑
                                    key = null
                                     │
                                    value = 25MB  ← 强引用，没人能访问到，但占着内存
                                        ↑
                                 ThreadLocalMap（线程一直活着）
```

### 案例：错误 vs 正确

**❌ 错误（泄漏版）：`ThreadLocalDemo2.errorThreadLocalDemo()`**

```java
threadLocalMyData.transactionLocal.set(threadLocalMyData.transactionLocal.get() + 1);
// 线程复用 → 第二次任务读到旧值 → 数据错乱 + 内存泄漏
```

**✅ 正确：`ThreadLocalDemo2.okThreadLocalDemo()`**

```java
try {
    threadLocalMyData.transactionLocal.set(...);
} finally {
    threadLocalMyData.transactionLocal.remove();  // ⚠️ 必须 remove！
}
```

---

## 四、ThreadLocal 清理流程

| 方法 | 何时触发清理 | 清理什么 |
|------|-------------|---------|
| `get()` | 调用时检测到 key=null | 清除 key=null 的 Entry |
| `set()` | 调用时检测到 key=null | 清除 key=null 的 Entry |
| `remove()` | 主动调用 | 清除当前线程的整个 Entry |
| `expungeStaleEntry()` | 内部方法 | 遍历清除所有 key=null 的 Entry |

---

## 五、四种引用 → 从强到弱逐级分析

> 辅助类：`MyReferenceObject` — 重写了 `finalize()`，回收时打印日志，用于观察 GC 行为。

### 5.1 强引用（Strong Reference）

```java
MyReferenceObject obj = new MyReferenceObject();  // 默认就是强引用
obj = null;  // 断开引用
System.gc(); // GC 才回收
```

| 特点 | 说明 |
|------|------|
| GC 策略 | **永远不回收**，只要引用还在 |
| 案例 | `ReferenceDemo` |
| 场景 | 99% 的 `new` 对象 |

---

### 5.2 软引用（Soft Reference）

```java
SoftReference<MyReferenceObject> softRef = new SoftReference<>(obj);
obj = null;
System.gc();  // 内存充足 → 不回收

// 分配 100MB 后再 GC → OOM 前才回收
```

| 特点 | 说明 |
|------|------|
| GC 策略 | **内存不够时才回收** |
| 案例 | `SoftReferenceDemo` |
| 场景 | 缓存（图片缓存、页面缓存） |

---

### 5.3 弱引用（Weak Reference）

```java
WeakReference<MyReferenceObject> weakRef = new WeakReference<>(obj);
obj = null;
System.gc();  // GC 来了 → 直接回收
```

| 特点 | 说明 |
|------|------|
| GC 策略 | **GC 一来就回收**，不等 |
| 案例 | `WeakReferenceDemo` |
| 场景 | ThreadLocal 的 key、WeakHashMap |

---

### 5.4 虚引用（Phantom Reference）

```java
ReferenceQueue<MyReferenceObject> queue = new ReferenceQueue<>();
PhantomReference<MyReferenceObject> phantomRef = new PhantomReference<>(obj, queue);
phantomRef.get();  // 永远返回 null  ← 关键

// 对象被 GC 后 → 这个 phantomRef 被放入 queue
// 另一个线程 poll queue → 收到通知 → 做善后
```

| 特点 | 说明 |
|------|------|
| GC 策略 | 对象回收后，引用进 ReferenceQueue 通知 |
| get() | **永远 null**，摸不到对象 |
| 案例 | `PhantomReferenceDemo` |
| 场景 | DirectByteBuffer 堆外内存释放 |

---

## 六、四种引用速记表

| 引用类型 | GC 回收时机 | 能否 get 到对象 | 案例类 | 一句话 |
|---------|-------------|:---:|--------|--------|
| **强引用** | 永不回收 | ✅ | `ReferenceDemo` | "死也不让你收" |
| **软引用** | OOM 前回收 | ✅ | `SoftReferenceDemo` | "内存够别动我" |
| **弱引用** | GC 即回收 | ✅ | `WeakReferenceDemo` | "GC 来了我马上走" |
| **虚引用** | 回收时通知 | ❌ 永远 null | `PhantomReferenceDemo` | "你死了通知我，好善后" |

---

## 七、ThreadLocal 面试四连答

### Q1：ThreadLocal 原理是什么？

> 每个线程内部有一个 `ThreadLocalMap`，key 是 ThreadLocal 对象的**弱引用**，value 是副本数据。Get/Put 都在当前线程的 Map 里操作，线程隔离，无锁访问。

### Q2：ThreadLocal 会造成内存泄漏吗？

> **会**——线程池复用线程，ThreadLocal 外部引消失后被 GC，但 ThreadLocalMap 中 key 变 null，**value 强引用还占着内存**。必须 `remove()` 或在 try-finally 中清理。

### Q3：为什么 ThreadLocal 的 key 用弱引用？

> 为了**防止 ThreadLocal 对象本身无法回收**。如果 key 是强引用，即使外部没用 ThreadLocal 了，只要线程还活着，ThreadLocalMap 中的 key 就一直引用它，ThreadLocal 对象就永远没法 GC。

### Q4：四种引用怎么记？

> **强→软→弱→虚**，越来越弱。强不死、软到 OOM 才死、弱见 GC 死、虚已经死了只通知收尸。

---

## 八、面试话术（一分钟版）

> **ThreadLocal 是线程局部变量，每个线程独享一份数据副本，线程隔离无锁访问。**
>
> **底层是 Thread 里的 ThreadLocalMap，key 是弱引用防泄漏，value 是强引用。**
>
> **线程池场景必须 `remove()`，否则 key 被 GC 后 value 永远占着内存——内存泄漏。**
>
> **四种引用逐级变弱：**
> - 强引用永不回收
> - 软引用 OOM 前回收（缓存）
> - 弱引用 GC 即回收（ThreadLocal key）
> - 虚引用回收后通知（堆外内存释放）
