# JUC 三大同步工具 — 面试总结

> 目录：`com.axon.java.stack.juc.threadUtils`

---

## 一、CountDownLatch — 倒计时门闩

### 核心

**一个或多个线程等待其他线程全部完成任务，才继续执行。**

```java
CountDownLatch latch = new CountDownLatch(10);  // 计 10 个数

// 10 个线程干活
for (int i = 0; i < 10; i++) {
    new Thread(() -> {
        doWork();
        latch.countDown();  // 干完减 1
    }).start();
}

latch.await();   // 主线程等，直到计数器=0
```

### 特点

| 要点 | 说明 |
|------|------|
| 方向 | 计数器**往下减**（10 → 9 → ... → 0） |
| 次数 | **一次性**，到 0 不能重置 |
| 背后 | AQS 共享锁，state 初始 = count，countDown 用 CAS 减，到 0 唤醒 await 线程 |

### 生活比喻

> 办公室保洁——等所有人都走了（计数器归零），才能打扫。

---

## 二、CyclicBarrier — 循环栅栏

### 核心

**多个线程互相等待，所有人都到齐，再一起往下走。**

```java
CyclicBarrier barrier = new CyclicBarrier(10, () -> {
    System.out.println("所有运动员都准备好了，开赛！");
});

// 10 个线程
for (int i = 0; i < 10; i++) {
    new Thread(() -> {
        prepare();
        barrier.await();  // 等别人，人齐了一起步
        startRace();
    }).start();
}
```

### 特点

| 要点 | 说明 |
|------|------|
| 方向 | 人等别人（凑够 10 个），**凑到数才走** |
| 次数 | **可重复使用**（"循环"的），10 人过后回归，下一轮继续 |
| 可选回调 | 构造器第 2 个参数传 Runnable，**人齐后自动触发** |

### 生活比喻

> 打麻将三缺一——四个人必须都到齐，才能坐下来打。下一轮还能继续凑。

---

## 三、Semaphore — 信号量 / 抢车位

### 核心

**限制同时访问资源的线程数量。**

```java
Semaphore semaphore = new Semaphore(3);  // 3 个许可

// 6 个线程抢 3 个车位
for (int i = 0; i < 6; i++) {
    new Thread(() -> {
        semaphore.acquire();   // 抢许可
        doWork();
        semaphore.release();   // 还许可
    }).start();
}
```

### 特点

| 要点 | 说明 |
|------|------|
| 核心操作 | `acquire()` 拿许可，`release()` 还许可 |
| 许可没了 | `acquire()` 阻塞，等别人释放 |
| 公平性 | 构造器传 `true` → 公平模式，先排队的先拿许可 |

### 生活比喻

> 停车场 3 个车位 6 辆车——只能进 3 辆，出来一个进一个。

---

## 四、三者对比速记表

| | CountDownLatch | CyclicBarrier | Semaphore |
|---|---|---|---|
| **模型** | 一个人等一群人 | 一群人等齐再走 | 限制同时准入人数 |
| **计数方向** | 从 n 减到 0 | 从 0 加到 n | 从 n 减到 0 |
| **可重用？** | ❌ 一次性 | ✅ 可循环 | ✅ 可重用 |
| **核心方法** | `countDown()` / `await()` | `await()` | `acquire()` / `release()` |
| **生活比喻** | 等人走光再打扫 | 三缺一等凑齐 | 3 车位 6 辆车 |

---

## 五、面试话术

### 一句话区分

> **CountDownLatch 一个线程等别人，CyclicBarrier 所有人都等齐再走，Semaphore 限制同时进的人数。**

### 展开版（面向面试官）

> **CountDownLatch** 计数器从 N 往下减到 0，主线程 `await()` 等着。一次性，不可重置。典型场景：主线程等所有子线程执行完毕。
>
> **CyclicBarrier** 一组线程互相等，全部 `await()` 到齐后一起出发。可重复使用，人齐后可选触发回调。典型场景：多线程分阶段计算。
>
> **Semaphore** 控制并发访问线程数。`acquire()` 拿许可，`release()` 还许可。可选的公平模式。典型场景：连接池、限流。

---

## 六、快速记忆口诀

```
CountDownLatch： 一个人等一群人，一次性
CyclicBarrier： 一群人等齐再走，可循环
Semaphore：     限流控场，许可制
```
