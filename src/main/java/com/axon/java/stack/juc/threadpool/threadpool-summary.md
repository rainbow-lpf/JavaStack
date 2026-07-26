# 线程池 & 死锁 & FutureTask — 面试总结

> 目录：`com.axon.java.stack.juc.threadpool`

---

## 一、线程创建方式：Runnable vs Callable

### 案例：`MyThead.java`

| | Runnable | Callable |
|---|---|---|
| 方法 | `void run()` | `V call()` |
| 返回值 | ❌ 无 | ✅ 有 |
| 抛出检查异常 | ❌ | ✅ |
| 投喂给线程 | `new Thread(runnable)` | 需 `FutureTask` 包装 |

```java
// Runnable — 无返回值
class MyThreadRunnable implements Runnable {
    public void run() { ... }    // 无返回
}

// Callable — 有返回值
class MyThreadCallable implements Callable<Integer> {
    public Integer call() { return 10; }  // 有返回
}

// Callable 不能直接塞给 Thread，需要 FutureTask 包装
FutureTask<Integer> task = new FutureTask<>(new MyThreadCallable());
new Thread(task).start();
Integer result = task.get();  // 阻塞等待拿到返回值
```

### FutureTask 关键特性：同一个任务只执行一次

```java
FutureTask task = new FutureTask(new MyThreadCallable());

new Thread(task).start();  // 第一个线程 → 执行 call()
new Thread(task).start();  // 第二个线程 → 发现已执行，跳过

// 只打印一次 "我是有返回值的线程"
```

> **原理：** FutureTask 内部用 **CAS + 状态机** 控制。第一个线程 CAS 抢到执行权，后续线程发现状态不是 NEW，直接不执行。

**状态流转：**

```
NEW → COMPLETING → NORMAL  (正常完成)
NEW → COMPLETING → EXCEPTIONAL (抛异常)
NEW → INTERRUPTING → INTERRUPTED (被中断)
```

---

## 二、线程池

### 2.1 三种内置线程池（禁止使用！）

| 方法 | 特点 | 风险 |
|------|------|------|
| `newFixedThreadPool(n)` | 固定 n 个线程 | **无界队列** LinkedBlockingQueue → OOM |
| `newSingleThreadExecutor()` | 单线程串行 | 同上，无界队列 → OOM |
| `newCachedThreadPool()` | 无限扩线程 | **最大线程数** Integer.MAX_VALUE → CPU 飙满 |

> **阿规：** 强制使用 `ThreadPoolExecutor` 显式创建，不允许用 Executors。

---

### 2.2 ThreadPoolExecutor 7 个参数

```java
new ThreadPoolExecutor(
    corePoolSize,      // ① 常驻核心线程数
    maximumPoolSize,   // ② 最大线程数
    keepAliveTime,     // ③ 非核心线程空闲存活时间
    unit,              // ④ 时间单位
    workQueue,         // ⑤ 等待队列
    handler            // ⑥ 拒绝策略
    // 第7个参数：threadFactory（可选）
);
```

---

### 2.3 银行窗口比喻（面试必讲）

```
corePoolSize = 2         → 平时开 2 个窗口
maximumPoolSize = 5     → 最多开 5 个窗口
workQueue = 10          → 大厅 10 个座位
handler = AbortPolicy   → 大厅满、窗口满 → 甩门拒绝

流程：
1号客户 → 窗口1（正在处理）
2号客户 → 窗口2（正在处理）
3～12号  → 大厅 10 个座位等待
13号     → 开窗口3（扩容）  ← 队列满了才扩
14号     → 开窗口4
15号     → 开窗口5（全开了）
16号     → 窗口全满 + 队列满 → 拒绝策略，甩门！
```

> **关键：扩容发生在队列满之后，不是线程满了就扩。**

---

### 2.4 执行流程图

```
提交任务
   │
   ▼
核心线程数 < corePoolSize? ──YES──→ 创建核心线程执行
   │NO
   ▼
工作队列有空位？ ──YES──→ 塞进队列等待
   │NO
   ▼
总线程数 < maximumPoolSize? ──YES──→ 创建非核心线程执行
   │NO
   ▼
执行拒绝策略
```

---

### 2.5 最大线程数怎么设置

| 类型 | 判断标准 | 公式 |
|------|---------|------|
| **CPU 密集型** | 大量运算、加解密、编码 | `核心数 + 1` |
| **IO 密集型** | 数据库、Redis、文件、RPC 调用 | `核心数 / (1 - 阻塞系数)` 阻塞系数 0.8~0.9 |
| **IO 密集型简化版** | 大部分场景 | `核心数 × 2` |

```java
int cores = Runtime.getRuntime().availableProcessors();

// CPU 密集型
new ThreadPoolExecutor(cores + 1, ...)

// IO 密集型
new ThreadPoolExecutor(cores * 2, ...)
```

---

### 2.6 四种拒绝策略

| 策略 | 行为 |
|------|------|
| `AbortPolicy` | 抛 `RejectedExecutionException`（**默认**） |
| `CallerRunsPolicy` | 由**提交任务的线程**自己执行，防丢 |
| `DiscardPolicy` | 直接**丢弃**新任务，不报错 |
| `DiscardOldestPolicy` | 丢弃**队列里最老的那个**，再尝试提交 |

---

## 三、死锁

### 3.1 案例：`DeadLockDemo.java`

```java
// 线程AAA：先拿 lockA，再拿 lockB
synchronized (lockA) {
    Thread.sleep(1000);
    synchronized (lockB) { ... }
}

// 线程BBB：先拿 lockB，再拿 lockA   ← 顺序反了
synchronized (lockB) {
    Thread.sleep(1000);
    synchronized (lockA) { ... }
}
```

```
时间线：
AAA:   拿到 lockA ───────────→ 等 lockB
BBB:   拿到 lockB ───────────→ 等 lockA
         ↑                          ↑
         └──────── 互相等待 ─────────┘
                  死锁！
```

---

### 3.2 死锁四个必要条件（缺一不可）

| 条件 | 含义 |
|------|------|
| **互斥** | 资源同一时刻只能一个线程用 |
| **持有并等待** | 拿着一个，等另一个 |
| **不可剥夺** | 不能抢别人手里的 |
| **循环等待** | A 等 B，B 等 C，C 等 A |

---

### 3.3 排查方法

```bash
# 1. 找 Java 进程
jps -l

# 2. 打印线程堆栈，自动检测死锁
jstack <pid>

# 输出会明确标出
# Found one Java-level deadlock:
# "BBB": waiting to lock ... which is held by "AAA"
# "AAA": waiting to lock ... which is held by "BBB"
```

---

### 3.4 解决方案

| 方案 | 做法 |
|------|------|
| **固定顺序** | 所有线程按同一顺序加锁（如先 A 后 B） |
| **tryLock** | `ReentrantLock.tryLock(timeout)` 超时放弃 |
| **一次性申请** | 先申请所有资源，缺一直等 |

---

## 四、面试话术

### 线程池（30 秒版）

> **7 参数：** corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、handler、threadFactory。
>
> **流程：** 先看核心线程满没 → 没满新开，满了塞队列 → 队列满了再看线程总数到 max 没 → 没到开临时线程，到了走拒绝策略。
>
> **大小设置：** CPU 密集型 `核+1`，IO 密集型 `核×2`（或 `核/(1-阻塞系数)`）。
>
> **阿规：** 禁止用 Executors 直接创建，必须 `new ThreadPoolExecutor`。

### Runnable vs Callable（15 秒版）

> Runnable 无返回值无异常，Callable 有返回值有异常。Callable 不能直接给 Thread，必须用 FutureTask 包装。同一个 FutureTask 只执行一次——内部 CAS 控制。

### 死锁（20 秒版）

> **四个必要：** 互斥、持等、不夺、环等。**排查：** `jps -l` + `jstack pid`。**解决：** 固定加锁顺序、tryLock 超时。
