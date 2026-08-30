# ReentrantLock 与 AQS 原理

> lock/unlock 全流程、公平非公平、CLH 队列、为什么双向不环形——面试全链路

---

## 一、基础结构

```java
ReentrantLock
  └─ Sync extends AbstractQueuedSynchronizer
       ├─ state (volatile int)：0=空闲，1=首层持有，2,3...=重入层数
       └─ exclusiveOwnerThread：当前持有线程（继承自 AbstractOwnableSynchronizer）
```

- state 的修改全靠 **CAS**（`Unsafe.compareAndSwapInt`），保证原子性
- state 不只是"有没有锁"——它同时是**重入计数器**

---

## 二、lock() 流程（非公平，默认）

```
acquire(1)
 ① tryAcquire(1)：
     CAS state 0→1 成功？
     ├─ 是 → setExclusiveOwnerThread(当前线程)，结束
     ├─ state ≠ 0 且 owner == 当前线程？→ state+1（重入），结束
     └─ 都不是 → return false
 ② 失败 → addWaiter(Node.EXCLUSIVE)：包装成 Node 追加到 CLH 双向队列尾
 ③ acquireQueued：自旋判断"前驱是否 head"
     ├─ 是 → 再抢一次 tryAcquire
     └─ 不是/抢失败 → LockSupport.park(this) 挂起，等被唤醒后继续自旋
```

**记忆锚点：lock = CAS 抢 + 重入加 + 入队 park。**

---

## 三、unlock() 流程

```
release(1)
 ① tryRelease(1)：
     c = state - 1
     当前线程 != owner → 抛 IllegalMonitorStateException（没锁先放）
     c == 0？→ setExclusiveOwnerThread(null) + state=0，return true
     c > 0？ → 仅 state=c，return false     ← 重入未放完，不唤醒任何人
 ② true → unparkSuccessor(head)：LockSupport.unpark(队列第一个等待线程)
     → 该线程被唤醒，回到 lock 流程 ③ 自旋抢锁
```

**记忆锚点：unlock = 减一 + 归零才唤醒。重入 3 层 state=3，unlock 一次 state=2，锁根本没释放。**

---

## 四、常见错误回答纠正

| 典型错误回答 | 问题在哪 |
|-------------|---------|
| "lock 时 state=1" | 错。state=1 只是首次拿到；lock 是 **CAS 抢占 0→1**，抢不到还要判断重入（state+1），再抢不到入队 park——不是无脑赋值 |
| "unlock 时 state=1、持有者置空" | 错。unlock 是 **state-1**；减到 **0** 才置空 owner 并唤醒后继 |
| 只说"基于 AQS"不展开 | 没讲 CLH 队列、CAS、park/unpark；公平/非公平差异点在 tryAcquire 那几行，不在"有没有队列" |

---

## 五、公平 vs 非公平——差异只有一小段

```java
// 非公平 tryAcquire：上来直接 CAS 抢（插队 barging）
final boolean nonfairTryAcquire(...) {
    if (compareAndSetState(0, 1)) { setExclusiveOwnerThread(t); return true; }
    // 重入逻辑...
}

// 公平 tryAcquire：抢之前先看队列里有没有人在排
protected boolean tryAcquire(...) {
    if (hasQueuedPredecessors())          // ← 唯一区别：有人在排就不抢
        return false;
    // 后面 CAS + 重入逻辑一样
}
```

| | 非公平（默认） | 公平 |
|---|---|---|
| 抢锁方式 | 直接 CAS 插队 | 队列有人就让，按序 |
| 吞吐 | 高（减少线程切换） | 低 |
| 饥饿 | 可能 | 不会 |
| 唤醒后 | 可能被新来线程插队 | 一定按序 |

---

## 六、CLH 是什么

**Craig、Landin、Hagersten**（1990s，瑞典计算机研究所）发明的**队列自旋锁**算法。AQS 就是它的变体。

### 解决什么问题

早期 TAS（test-and-set）自旋锁：所有线程挤在同一把锁变量上自旋 → 同一个缓存行被所有 CPU 核来回抢 → **MESI 缓存一致性风暴**，总线打满，锁还没争到系统先垮。

### CLH 核心思想：排队 + 各自盯前驱

```
              tail（CAS 换指针入队）
               │
[A.locked=true] ← A 持有锁
   ↑
   └─ B 盯着 A：while (A.locked) 自旋
   ↑
   └─ C 盯着 B：while (B.locked) 自旋
```

```
① B 入队：tail 原子指向 B，B.prev = A     （O(1) CAS）
② B 自旋：盯的是【前驱 A 的节点】，不是全局锁变量
   → 每个线程盯的缓存行都不同 → 没有总线风暴
③ A 释放：把自己节点的 locked 置 false
④ B 看见 → 自己成为持锁者，C 转而盯 B
```

**每个线程只看前驱的"状态牌"，牌挂在自己节点上，别人翻牌我才动。**

### 三个性质

| 性质 | 说明 |
|------|------|
| FIFO 公平 | 先入队先获得，天然无饥饿 |
| 自旋分散 | 每人只盯前驱，冲突分散到各自缓存行 |
| 入队/出队 O(1) | 一次 CAS 换 tail / 移指针 |

### 锁算法定位表

| 锁算法 | 自旋在哪 | 问题 |
|--------|---------|------|
| TAS 自旋锁 | 全局同一变量 | 缓存行风暴 |
| Ticket 锁 | 全局序号 | 还是同一变量，释放时惊群 |
| **CLH** | **前驱的节点** | ✅ 分散；但单向、取消难 |
| MCS | 自己的节点 | 也分散；但取消/超时更难实现 |

---

## 七、AQS 队列为什么是双向链表（而不是单向/环形）

先纠偏：AQS 用的**不是环形队列**，是 **CLH 变体——双向、非环形的链表**。面试官问的是"为什么双向而不用单向"（原版 CLH 就是单向的）。

```
head ↔ Node ↔ Node ↔ Node ← tail
        双向：每个 Node 有 prev 和 next 两个指针
```

### 原因 1：取消节点要 O(1) 摘除自己

线程等锁时会被**中断/超时**，`cancelAcquire` 要把自己从队列中间摘掉：

```
A ↔ B ↔ C        B 被中断取消
    ↑ 单向链表：B 想摘掉自己，找前驱 A 要从 head 遍历 → O(n)
    ↑ 双向链表：B.prev 直接拿到 A → O(1) 摘除
```

### 原因 2：唤醒后继时，next 指针不可信，必须反向遍历

`unparkSuccessor(head)` 找第一个有效后继时，**head.next 可能是 null 或已取消**：

```
时序坑：
enq() 里 CAS 设置 tail → 新节点先连自己的 prev
                        → 然后才设置旧尾节点的 next
        ↑ 这个窗口期，从 head 往后走会"断链"（next == null）

另外中途取消的节点 next 指向已无效节点

解法：从 tail 沿 prev 反向扫，找到离 head 最近的非 CANCELLED 节点唤醒
        ↑ 这个操作只有双向链表能做
```

**单向链表这两个需求都做不到 O(1)，这就是 Doug Lea 把 CLH 从单向改成双向的原因。**

### 原版 CLH vs AQS 变体

| | 原版 CLH | AQS 变体 |
|---|---------|---------|
| 链表 | 单向 | **双向** |
| 等待方式 | 在前驱状态上**自旋** | **park 挂起**（不烧 CPU） |
| 节点携带 | 只有状态 | thread、waitStatus、前后指针、nextWaiter |
| 队列 | 隐式（逻辑存在） | 显式 head/tail，CAS 维护 |
| 取消/超时 | 难 | 双向链表 O(1) 摘除 |

选 CLH 而不是 MCS：CLH 只需看**前驱**状态，结构简单、取消和超时容易实现。

### 为什么不是"环形"

环形（RingBuffer）是**有界循环缓冲**结构（如 `ArrayBlockingQueue`、Disruptor），适合**固定容量 + 生产消费循环复用槽位**。AQS 等待队列：

- 长度动态、无上界（等待线程数不可预知）
- 语义是"排队叫号"，出队即废弃节点，不循环复用
- 操作只有**头出尾进**（+中间取消），没有循环遍历需求

环形在这里毫无收益。

---

## 八、ReentrantLock 遵循锁升级策略吗？

### 结论

**不遵循。"锁升级"（偏向锁 → 轻量级锁 → 重量级锁）是 JVM 层面 synchronized 的专属机制**，作用于**对象头 Mark Word**，由 JVM 自动完成。

`ReentrantLock` 是 **JDK API 层**的纯 Java 实现（AQS = `state` + CAS + CLH 队列 + `park/unpark`），没有 Mark Word、没有 Monitor，**不存在锁升级**——加锁路径从一而终：

```
CAS 抢 state → 抢不到入 CLH 队列 → 自旋几下（有限次）→ park 挂起
```

### 两者对比

| | `synchronized` | `ReentrantLock` |
|---|----------------|-----------------|
| 层面 | JVM 内置（字节码 monitorenter/exit） | JDK 类库（AQS） |
| 锁数据 | 对象头 Mark Word + ObjectMonitor | AQS 的 `volatile state` |
| **锁升级** | ✅ 无锁→偏向→轻量→重量 | ❌ 无此概念 |
| 优化手段 | JVM 自适应自旋、锁消除、锁粗化 | CAS 自旋失败即 park，不烧 CPU |
| 公平性 | 只有非公平 | 公平/非公平可选 |
| 重入 | 都支持（Mark Word 记录 owner+计数 / state 计数） | 同 |

### 两个容易混淆的点

#### 1. ReentrantLock 的"自旋" ≠ 锁升级

`acquireQueued` 抢不到锁时会**自旋几次**（前驱是 head 时再试 CAS），失败才 park——这只是**减少挂起/唤醒开销的优化**，不是"轻量级锁升级成重量级锁"的形态转换。

#### 2. 真正存在的是"锁降级"——属于 `ReentrantReadWriteLock`

```java
rw.writeLock().lock();      // 持有写锁
rw.readLock().lock();       // 写锁内再拿读锁
rw.writeLock().unlock();    // 释放写锁 → 此时只持读锁 = 降级 ✅
```

- **可以降级**：写 → 读（保证写完的数据立即可见，不被别的写插队）
- **不能升级**：持读锁直接要写锁 → 两个线程都这么干会**互相等对方释放读锁 → 死锁**

### 话术（30 秒）

> 不遵循。锁升级是 synchronized 的 JVM 机制——对象头 Mark Word 从偏向锁到轻量级锁到重量级锁的膨胀过程；ReentrantLock 是 API 层基于 AQS 的纯 Java 实现，state + CAS + CLH 队列 + park，没有 Mark Word 也就没有升级链。它抢锁失败自旋几下就 park 挂起，不烧 CPU。"升级"和 ReentrantLock 无关，但"降级"概念在 ReentrantReadWriteLock 里有——写锁内拿读锁再放写锁即降级，反过来读升写会死锁。

## 九、面试总结话术

### lock/unlock 原理（30 秒）

> ReentrantLock 基于 AQS：state 表持有计数（0 空闲，N 重入层数），exclusiveOwnerThread 记持有者，state 修改全靠 CAS。lock 走 acquire：先 tryAcquire——非公平直接 CAS 抢，公平先查 hasQueuedPredecessors；抢不到入 CLH 双向队列 park 挂起，醒来后自旋再抢。unlock 走 release：state-1，减到 0 才清 owner 并 unpark 队列头后继；重入没放完则只减计数不唤醒。公平非公平的唯一区别在 tryAcquire 前是否检查排队前驱。

### CLH 与双向链表（30 秒）

> CLH 是"FIFO 排队 + 每人盯前驱状态"的队列自旋锁，解决 TAS 锁缓存行风暴的问题。AQS 借了它的排队骨架改三点：单向→双向、自旋→park、隐式队列→显式 Node 链表。改双向的原因有二：一，取消/超时时 O(1) 摘除自己；二，唤醒后继时 head.next 可能因入队 CAS 时序窗口为 null 或指向已取消节点，必须从 tail 沿 prev 反向扫描——只有双向能反向遍历。不是环形：环形是有界循环缓冲结构，等待队列动态无界、出队即废弃，没有循环复用语义。
