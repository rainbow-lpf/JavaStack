# 线程池专题

> 队列评估、选型、监控打点、动态配置——线程池面试全链路

---

## 一、执行顺序（一切分析的前提）

`ThreadPoolExecutor` 提交任务的执行顺序（面试必考，很多人答错）：

```
新任务提交
  ├─ 核心线程未满 → 创建核心线程执行
  ├─ 核心线程满 → 任务入队
  ├─ 队列满 → 创建非核心线程（直到 maxPoolSize）
  └─ 达到 maxPoolSize → 触发拒绝策略
```

**推论：队列太大会让 `maxPoolSize` 形同虚设** —— 任务全在排队，线程永远扩不上去，表现为：线程数很低 + 队列堆积 + RT 飙高。所以"队列配多大"本质是**缓冲突发 vs 快速背压**的权衡。

---

## 二、有界队列大小怎么评估

### 评估五步法

#### 第 1 步：摸清任务三要素

```
λ  = 任务到达速率（QPS）
T  = 单任务执行平均耗时（纯执行，不含排队等待）
RT = 响应时间（Response Time）= 排队等待 + 执行 T
SLA = 可接受的 RT 预算
```

#### 第 2 步：先定线程数（队列评估的前提）

```
CPU 密集：线程数 = N核 + 1
IO 密集：线程数 = N核 × (1 + 等待时间/计算时间)
```

两个时间指什么（单任务总耗时 = 等待 + 计算）：

| 时间 | 线程状态 | 典型来源 |
|------|---------|---------|
| 计算时间 | `RUNNABLE`，真在烧 CPU | 反序列化、业务计算、加密、组装对象 |
| 等待时间 | 挂起，CPU 让给别人 | 等 DB 返回、HTTP/RPC、Redis、文件 IO、抢锁 |

推导直觉：每线程 CPU 占用率 = 计算时间 / 总耗时；要喂饱 N 个核 → 线程数 = N ÷ 占用率 = N × (1 + 等待/计算)。例：总 100ms = 等 80ms + 算 20ms → 4 核需 4 × (1+4) = 20 线程。

三个坑：① 下游连接池必须 ≥ 线程数，否则卡在拿连接公式白算；② CPU 目标利用率按 ~80% 留余量；③ 等待大头在下游服务时，加线程只是排队前移，该扩下游或加缓存。

> 算出的是**稳态并发线程数**，通常落在 core~max 之间。稳态值定 core，峰值余量定 max（示例：稳态 20 → core=20, max=40）。

#### 第 3 步：用 RT 预算倒推队列容量（核心公式）

```
排队延迟 ≈ 队列长度 × T / 线程数        ← 线程数取稳态并发数，保守可按 max 代入
→ 队列长度 = 线程数 × 允许排队延迟 / T
→ 允许排队延迟 = RT 预算 - T

注意：排队延迟只是"等的那段"，单个任务的完整响应时间：
RT = 排队延迟 + T = 队列长度 × T / 线程数 + T
```

#### 第 4 步：叠加突发余量 + 内存校验

前 1~3 步只算匀速稳态。第 4 步回答两个问题：**突发兜得住吗？队列打满那一刻内存扛得住吗？**

**① 突发校验**

```
突发量 = 突发系数 × 突发持续时间 × 稳态 QPS
积压消化时间 = 积压任务数 × T / 线程数
```

突发来源：定时任务集中触发、缓存集中失效、上游重试风暴、MQ 批量拉取、活动脉冲。

- **短毛刺**（几十~几百 ms）：消化时间 < RT 预算 → 队列吸收 ✅，这是队列的本职
- **长突发**（秒级以上）：队尾任务排队超 RT 预算，堆队列只是"把拒绝换成超时" ❌ → 换手段：上游限流（Sentinel/网关）、扩线程（core→max 就是为此留的）、异步削峰（落 MQ 匀速消费）

**队列是"缓冲区"不是"仓库"——能缓冲毛刺，扛不住持续超载。**

**② 内存校验（按队列打满的最坏情况算）**

```
内存占用 = 队列长度 × 单任务对象大小（retained size，含任务持有的整个引用图）
```

- 轻任务（只捕获 id）：队列 100 个 ≈ 几十 KB，忽略
- 重任务（闭包捕获 1MB 报文/万条实体 List）：100 × 1MB = 100MB/池，多池叠加直接压老年代 ⚠️
- 校验动作：估算占用 < 堆的 10%~20%；压测故意打满队列观察老年代；大对象任务改捕获轻量参数（id 而非报文）

**有界队列的第二重意义：既是背压阀，也是内存保险丝。** 无界队列 + 大任务对象 = 经典 OOM 组合。

#### 第 5 步：压测定稿 + 监控兜底

上线观察队列水位（`getQueue().remainingCapacity()`）、拒绝次数、RT P99 → 参数接配置中心动态调。

### 完整算例

```
4C8G，IO 密集，QPS = 1000，单任务 20ms，RT 预算 200ms

① 线程数：4 × (1 + 80ms/20ms) = 20
   （20 线程 × 50 次/秒 = 1000 QPS，正好消化稳态流量）

② 允许排队延迟 = 200 - 20 = 180ms，保守取 100ms
   队列 = 20 × 100 / 20 = 100

③ 突发校验：2 倍流量持续 1 秒 = 需缓冲 2000 → 远超 100
   → 结论：突发靠上游限流扛，不靠队列堆
   → 队列只吸收毫秒级毛刺，100 够用

④ 内存：100 个任务 × 几 KB = 忽略不计 ✅

⑤ 定稿：core=20, max=40, queue=100, 压测调优
```

**一句话：队列容量 = 线程数 × 可接受排队延迟 ÷ 单任务耗时。队列是吸收毛刺的缓冲区，不是堆任务的仓库。**

---

## 三、有界 vs 无界 vs SynchronousQueue 清单

| 队列 | 容量 | 分类 | 备注 |
|------|------|------|------|
| `ArrayBlockingQueue` | 构造必传 capacity | **有界** | 数组实现，预分配内存，GC 友好 |
| `LinkedBlockingQueue(capacity)` | 传了就是有界 | **有界** | 推荐显式传，如 `new LinkedBlockingQueue<>(1000)` |
| `LinkedBlockingQueue()` ⚠️ | `Integer.MAX_VALUE`（约 21 亿） | **无界** | 大坑，见下 |
| `LinkedBlockingDeque` | 同上，传参才有效 | 默认**无界** | 双端，work-stealing 可用 |
| `PriorityBlockingQueue` | 动态扩容 | **无界** | 有优先级排序，任务堆积风险大 |
| `DelayQueue` | 动态扩容 | **无界** | 延时任务专用（定时器场景） |
| `LinkedTransferQueue` | 动态扩容 | **无界** | `size()` 是遍历统计，别当水位监控用 |
| `SynchronousQueue` | **0** | 特殊：既非有界也非无界 | 零容量直接交接，永不排队 |

### 关键陷阱：`LinkedBlockingQueue` 不传参就是无界

```java
new LinkedBlockingQueue<>();  // 容量 = Integer.MAX_VALUE ≈ 21亿
```

`Executors` 四个工厂方法全是坑：

```java
newFixedThreadPool(10)
  → LinkedBlockingQueue() 无界      // 任务堆积 → OOM

newSingleThreadExecutor()
  → LinkedBlockingQueue() 无界      // 同上

newCachedThreadPool()
  → SynchronousQueue                // 不堆积，但线程数 = Integer.MAX_VALUE → 线程爆炸

newScheduledThreadPool()
  → DelayedWorkQueue 无界           // 同样有堆积风险
```

**这也是阿里 Java 开发手册强制要求手动 `new ThreadPoolExecutor` 的原因。**

---

## 四、三种队列的场景选择

### 决策树（面试直接画这个）

```
任务允许丢/拒绝吗？
│
├─ 绝不能丢 → 落 MQ/DB 持久化（首选）
│             └─ 任务量有上界且消费恒快于生产 → 无界队列可接受
│
└─ 可以拒绝/反压
    ├─ 任务极快、延迟敏感、不想排队 → SynchronousQueue
    └─ 任务耗时波动、下游慢 IO、需要缓冲 → 有界队列 + 拒绝策略
```

### 有界队列 —— 默认选择，覆盖 90% 在线服务

| 场景 | 原因 |
|------|------|
| Web/RPC 请求处理 | RT 敏感，排队越久超时越多 |
| 下游是慢 IO（DB/HTTP） | 耗时波动大，必须有上限防雪崩 |
| 需要故障隔离 | 宁可拒绝几个请求，不能拖垮整个服务 |
| 消费能力 < 生产能力可能发生 | 必须背压 |

配套：拒绝策略选 `CallerRunsPolicy`（保任务）或自定义（打点 + 降级 + 告警）。

### 无界队列 —— 仅限"任务不能丢 + 量可控"

| 场景 | 为什么无界安全 |
|------|---------------|
| 单线程串行写审计日志 | 生产速率 < 消费速率，永远排不满 |
| 内部事件总线，事件源速率有上限 | 量可控 |
| 离线批处理任务 | 不在乎延迟，只在乎不丢 |

**前提必须说清：确认消费速率恒 > 生产速率，或任务总量有硬上界。否则就是 OOM 定时炸弹。**

更稳的替代：任务持久化到 MQ/DB，线程池只做搬运工，天然不丢 + 不怕堆积。

### SynchronousQueue —— 交接场景，零延迟优先

| 场景 | 为什么 |
|------|--------|
| 任务极快（微秒~毫秒级） | 排队等待比创建线程还亏 |
| 线程间直接交接活 | 生产者干完一块直接递给消费者，手递手 |
| 配合有界 maxPoolSize | 突发时快速扩线程而不是排队 |

⚠️ 用它的两个前提：

1. **maxPoolSize 必须设合理上限**（别学 `newCachedThreadPool` 的 `Integer.MAX_VALUE`，线程爆炸）
2. **必须配拒绝策略兜底**（常见 `CallerRunsPolicy` 反压上游，但要接受调用线程被占用的代价）

---

## 五、SynchronousQueue 细节

### 发音

**音标：** `/ˈsɪŋkrənəs kjuː/` → 中文读法：**辛-克若-呢斯 · Q（克尤）**

| 部分 | 音标 | 中文谐音 |
|------|------|---------|
| Syn- | /sɪŋ/ | **辛**（重音在这） |
| -chronous | /krənəs/ | **克若呢斯**（弱读，词根同 chronos 时间） |
| Queue | /kjuː/ | 就是英语字母 **Q** 的读音 |

⚠️ Queue = Q，一个音节，不要逐字母拼。

### 容量是 0，不是 1

> *"A synchronous queue does not have any internal capacity, **not even a capacity of one**."*
> —— `java.util.concurrent.SynchronousQueue` 官方文档原话

```java
SynchronousQueue<Integer> q = new SynchronousQueue<>();
q.size();               // 0
q.isEmpty();            // true
q.remainingCapacity();  // 0
q.offer(1);             // false —— 没有消费者等着，直接失败
q.peek();               // null  —— 根本不存元素
```

它不存储元素，是**直接交接队列**：

```
put(元素)  →  必须阻塞等另一个线程 take()
take()    →  必须阻塞等另一个线程 put()
```

### 面试官说"应该是 1"怎么应对

| 混淆来源 | 实际情况 |
|---------|---------|
| `ArrayBlockingQueue(1)` | 有界队列，容量 1，能存元素 |
| Go 语言 `make(chan T, 1)` | 带缓冲 channel，容量 1；`make(chan T)` 无缓冲才等价 SynchronousQueue |
| "一次只交接一个元素"的误解 | 错——多个生产者/消费者可以**同时并发交接**，内部存的是**等待的线程**，不是元素 |

应对话术（拿证据 + 给台阶）：

> "我印象里 Javadoc 明确写了 'not even a capacity of one'，`size()` 和 `remainingCapacity()` 都是 0，可以现场写个 demo 验证。您说的 1 是不是指 `ArrayBlockingQueue(1)` 或者 Go 的带缓冲 channel？"

---

## 六、线程池监控：打点 → 上报 → 告警

### 监控指标清单

| 指标 | API | 告警阈值建议 |
|------|-----|-------------|
| 活跃线程数 | `getActiveCount()` | 持续 ≈ maxPoolSize → 告警 |
| 队列水位 | `getQueue().size()` / `remainingCapacity()` | > 80% → 告警 |
| 已完成任务数 | `getCompletedTaskCount()` | 算吞吐趋势 |
| **拒绝次数** | 自定义 Handler 里打点 | **> 0 就告警** |
| 任务执行耗时 | 包装 Runnable 记录 | P99 超阈值 → 告警 |
| **排队耗时** | 提交时间戳 vs 开始执行时间差 | 持续上涨 → 说明该扩容了 |

**排队耗时最容易被忽略**——线程数够不够、队列合不合适，它是最灵敏信号。

### 打点上报 Demo（面试可手写版）

#### 1. TaskWrapper —— 记录提交/开始时间，算出两个耗时

```java
public class TaskWrapper implements Runnable {

    private final Runnable delegate;
    private final long submitAt = System.nanoTime();
    private volatile long startAt;

    public TaskWrapper(Runnable delegate) {
        this.delegate = delegate;
    }

    @Override
    public void run() {
        startAt = System.nanoTime();
        delegate.run();
    }

    /** 排队耗时：从提交到真正开始跑 */
    public long queuedMillis() {
        return (startAt - submitAt) / 1_000_000;
    }

    /** 执行耗时：从开始跑到 afterExecute 时刻 */
    public long execMillis(long endAt) {
        return (endAt - startAt) / 1_000_000;
    }
}
```

#### 2. MonitoredThreadPool —— 钩子打点 + 拒绝计数

```java
public class MonitoredThreadPool extends ThreadPoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(MonitoredThreadPool.class);

    private final String name;
    private final int queueCapacity;
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong slowTaskCount = new AtomicLong();   // 慢任务计数

    public MonitoredThreadPool(String name, int core, int max, int queueCapacity) {
        super(core, max, 60, TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(queueCapacity),
              r -> new Thread(r, name + "-worker"),
              (r, executor) -> {                       // ① 拒绝打点 + 降级
                  MonitoredThreadPool pool = (MonitoredThreadPool) executor;
                  pool.rejectedCount.incrementAndGet();
                  log.warn("[{}] REJECTED. queue={}/{}, active={}",
                          name, pool.getQueue().size(), pool.queueCapacity, pool.getActiveCount());
                  // 降级动作按业务选：落 MQ / 存库重试 / CallerRuns
                  if (!executor.isShutdown()) r.run();
              });
        this.name = name;
        this.queueCapacity = queueCapacity;
    }

    @Override
    public void execute(Runnable command) {
        super.execute(new TaskWrapper(command));         // ② 提交时包一层
    }

    @Override
    protected void afterExecute(Runnable r, Throwable ex) {
        long endAt = System.nanoTime();
        TaskWrapper w = (TaskWrapper) r;
        long queued = w.queuedMillis();
        long exec = w.execMillis(endAt);

        // ③ 任务级打点：真实系统里换成 Micrometer
        // Metrics.timer("tp.task.exec", "pool", name).record(exec, MILLISECONDS);
        // Metrics.timer("tp.task.queued", "pool", name).record(queued, MILLISECONDS);
        if (exec > 500) {                                // 慢任务阈值
            slowTaskCount.incrementAndGet();
            log.warn("[{}] SLOW task: queued={}ms, exec={}ms", name, queued, exec);
        }
        if (ex != null) {                                // ④ 异常默认被吞，必须补
            log.error("[{}] task failed", name, ex);
        }
    }

    /** ⑤ 周期性水位上报 —— 接 Prometheus 就是这段采集逻辑 */
    public void startReport() {
        new ScheduledThreadPoolExecutor(1, r -> new Thread(r, name + "-reporter"))
            .scheduleAtFixedRate(this::report, 5, 5, TimeUnit.SECONDS);
    }

    private void report() {
        int queueSize = getQueue().size();
        int usedPct = queueSize * 100 / queueCapacity;

        // 指标系统版本（Micrometer gauge，替换 log 即可）：
        // Metrics.gauge("tp.active", this, ThreadPoolExecutor::getActiveCount);
        // Metrics.gauge("tp.queue.used", this, p -> p.getQueue().size());
        // Metrics.gauge("tp.rejected", rejectedCount, AtomicLong::doubleValue);

        log.info("[{}] active={}/{}, queue={}/{}({}%), completed={}, rejected={}, slow={}",
                name, getActiveCount(), getMaximumPoolSize(),
                queueSize, queueCapacity, usedPct,
                getCompletedTaskCount(), rejectedCount.get(), slowTaskCount.get());

        if (usedPct > 80) {
            log.error("[{}] QUEUE NEARLY FULL ({}%), 触发告警: 建议扩容或检查下游",
                      name, usedPct);
        }
        if (rejectedCount.get() > 0) {
            log.error("[{}] has {} rejections, 触发告警", name, rejectedCount.get());
        }
    }
}
```

#### 3. 使用

```java
MonitoredThreadPool pool =
        new MonitoredThreadPool("order-pay-pool", 20, 40, 100);
pool.startReport();

for (int i = 0; i < 200; i++) {
    pool.execute(() -> {
        try { Thread.sleep(20); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
}
```

#### 运行输出长这样

```
[order-pay-pool] active=40/40, queue=58/100(58%), completed=102, rejected=0, slow=3
[order-pay-pool] REJECTED. queue=100/100, active=40
[order-pay-pool] QUEUE NEARLY FULL (81%), 触发告警: 建议扩容或检查下游
```

### 采集结构总结（面试 30 秒讲清）

```
任务级打点（三条钩子拼出任务完整生命周期）：
  execute()          → 提交时包装，记 submitAt（排队耗时起点）
  afterExecute()     → 任务结束，算 queued/exec 耗时、兜异常
  拒绝策略 Handler   → 拒绝计数 + 降级动作

时间轴：
  submitAt(execute包装时) ──排队──▶ startAt(run开始) ──执行──▶ afterExecute(算账)

池级水位（独立一条线）：
  ScheduledExecutor 定时 → getActiveCount() / getQueue().size() / 拒绝计数 → 上报

上报 → Micrometer → Prometheus → 告警规则
（队列水位 > 80% ｜ 拒绝 > 0 ｜ 活跃线程 ≈ max ｜ 排队 P99 超阈值）
```

**没有告警规则，前两步只是数字堆。监控的终点是"异常时有人被叫醒"。**

### 现场排查工具

- **Arthas**：`thread -n 5`（最忙线程）、`thread --state BLOCKED`（死锁/热点）
- **jstack**：看线程名定位业务池（所以 ThreadFactory 必须给线程池起名）

---

## 七、Apollo 动态配置线程池

参考美团《Java 线程池实现原理及其在美团业务中的实践》，开源实现有 **DynamicTp、Hippo4j**。

### ⚠️ `queueCapacity` 原生不支持动态修改

JDK 只提供运行时改这些：

```java
pool.setMaximumPoolSize();   // ✅
pool.setCorePoolSize();      // ✅
pool.setKeepAliveTime();     // ✅
pool.allowCoreThreadTimeOut();
// 没有 setQueueCapacity() ！！ LinkedBlockingQueue 的 capacity 是 final
```

**解法（美团方案）：自定义 ResizableCapacityLinkedBlockingQueue**，去掉 `capacity` 的 final，提供 `setCapacity()`；缩容且队列里有元素时保留原有元素不受影响。或者直接用 DynamicTp/Hippo4j。

### Apollo 监听实现骨架

```java
@ApolloConfigChangeListener
private void onChange(ConfigChangeEvent event) {
    if (event.isChanged("threadpool.core")) {
        int newCore = Integer.parseInt(event.getChange("threadpool.core").getNewValue());
        int newMax  = ...;

        // 顺序规则：维持 core ≤ max 恒成立，谁违反约束谁先动
        if (newCore > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(newMax);       // 先抬 max
            pool.setCorePoolSize(newCore);
        } else {
            pool.setCorePoolSize(newCore);         // 缩容场景先降 core
            pool.setMaximumPoolSize(newMax);
        }
        queue.setCapacity(newQueueCapacity);       // 自定义 Resizable 队列
        log.info("threadpool updated: core={}, max={}, queue={}", ...);
    }
}
```

### 调整顺序规则（易错点）

| 场景 | 顺序 |
|------|------|
| 新 core > 当前 max | **先 setMax，再 setCore** |
| 新 max < 当前 core | **先 setCore，再 setMax** |
| 其他 | 顺序无所谓 |

本质就一条：**任何瞬间不能让 core > max，否则 `IllegalArgumentException`**。

另外：缩容时已存在的多余线程**不会立刻杀**，等它们空闲（keepAliveTime）后回收。

### 落地要点

| 要点 | 说明 |
|------|------|
| **统一注册中心** | 所有池集中管理（ThreadPoolManager），否则散落各处刷不到 |
| **先 max 后 core（或先 core 后 max）** | 顺序错了抛异常 |
| **缩容不立即生效** | 已创建线程等 keepAliveTime 才回收 |
| **变更留痕** | 打日志 + 打点，方便回溯调参效果 |
| **按场景配置** | 日常/大促/夜间批处理不同 profile，活动前一键切大 |

---

## 八、面试总结话术

### 队列评估与选型

> 队列大小不拍脑袋：先用 Little's Law 和 RT 预算算 `队列 = 线程数 × 允许排队延迟 ÷ 耗时`，再校验突发和内存，最后压测定稿、监控动态调。选型上：**在线服务默认有界队列**（背压防雪崩）；无界只给"任务不能丢且量可控"的内部场景，更稳是落 MQ；`SynchronousQueue` 用于任务极快的交接场景，必须配 maxPoolSize 上限和拒绝策略。核心思想：**队列做缓冲、拒绝做背压、监控保水位**。

### 监控与动态配置

> 监控四件套：队列水位、活跃线程、拒绝次数、任务/排队耗时，钩子 + 包装任务 + 定时上报接 Prometheus，拒绝必告警。动态调参是推崇的方案——JDK 原生支持 setCore/setMax，配合 Apollo 监听器热更新；但 queueCapacity 原生改不了，需要自定义 ResizableCapacityLinkedBlockingQueue 或直接上 DynamicTp/Hippo4j。核心价值：**让线程池从"上线即定死"变成"可观测、可治理"**。
