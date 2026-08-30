# Kafka 存储与消息定位

> 从"一条消息发进来存成什么样"到"一个拉取请求怎么一步步找到它"，彻底搞懂 Kafka 的磁盘结构与寻址

---

## 〇、先和 RocketMQ 摆一起看

RocketMQ 你已经吃透了，链条是：

```
consumerOffset.json 拿 offset
  → offset × 20 定位 ConsumeQueue 索引（稠密，每条都有）
  → 索引里 offsetPy + sizePy
  → 去 CommitLog 读正文
```

Kafka 是**同一套思想，但两个地方不一样**：

```
① Kafka 不把所有消息塞一个 CommitLog，而是【每个分区一个独立目录】
② Kafka 的索引是【稀疏】的，不是每条消息都记
   → 所以定位时多了一步"先找大概位置，再顺序扫到精确位置"
```

记住这个对比，下面每一步都回到它。

---

## 一、存储全景：分区 = 一个目录

```
log.dirs（数据根目录）
└── order-0/                          ← topic=order 的 partition 0
    ├── 00000000000000000000.log      ← 段：消息正文
    ├── 00000000000000000000.index    ← 段：偏移索引
    ├── 00000000000000000000.timeindex← 段：时间索引
    ├── 00000000000000000300.log      ← 下一个段
    ├── 00000000000000000300.index
    ├── 00000000000000000300.timeindex
    ├── 00000000000000000600.log      ← 再下一个段
    └── ...
```

关键结论：

```
① 每个 partition 一个目录（和 RocketMQ 的 consumequeue/order/queue0/ 一个道理）
② 目录里消息不是一个大文件，而是切成一段一段的【段（segment）】
③ 每个段有三件套：.log（正文）+ .index（偏移索引）+ .timeindex（时间索引）
```

---

## 二、段文件名就是"起始 offset"（最关键的一步）

先看 offset 是什么：**消息的编号，从 0 递增，只增不减**。

```
order-0 里依次写入的消息：
  offset=0   "订单创建"
  offset=1   "订单支付"
  offset=2   "订单发货"
  ...
  offset=300 "第 300 条"
  offset=301 ...
  offset=500 "第 500 条"   ← 我们要找的这条
```

段文件名的数字 = **这个段里第一条消息的 offset（baseOffset）**：

```
00000000000000000000.log  → baseOffset = 0    （存 offset 0~299）
00000000000000000300.log  → baseOffset = 300  （存 offset 300~599）
00000000000000000600.log  → baseOffset = 600  （存 offset 600~...）

为什么分段？一个文件无限大没法管，写满 1GB（log.segment.bytes 默认）
或到了一定时间（log.roll.ms）就滚一个新段。
```

**这个"文件名 = 起始 offset"的设计，就是后面二分查找的地基。**

---

## 三、三件套各自存什么

### .log —— 消息正文

```
append-only，只往后追加。
里面是一条条"消息批次（RecordBatch）"：
  ┌ 批次头（含 baseOffset、lastOffset、recordCount 等）┐
  │ 记录1（offset=300）                                │
  │ 记录2（offset=301）                                │
  └ ...                                               ┘
  下一个批次...
```

### .index —— 偏移索引（稀疏）

```
把"offset"翻译成".log 里的字节位置"。

每条索引 8 字节：
  ┌ 4 字节 相对 offset（= 真实 offset - baseOffset）┐
  └ 4 字节 .log 里的物理位置（字节偏移）             ┘

稀疏：不是每条消息都记！
  默认每攒够 4KB 数据（log.index.interval.bytes=4096）才记一条索引
```

### .timeindex —— 时间索引

```
按"时间戳"找消息用（回溯按时间点定位就靠它）。
每条 12 字节 = 8B 时间戳 + 4B 相对 offset。

作用：把"时间"翻译成"offset"
  想按时间回溯时（不知道 offset，只知道大概几点），
  先在 .timeindex 里二分找到该时间对应的 offset，
  再把这个 offset 交给正常读取流程（.index + .log）。
```

**按时间找消息的典型场景：**

```
① 数据回溯 / 修复：新功能上线有 bug 把数据搞坏
   → 把消费者 offset 重置到"昨天凌晨 0 点"重新消费恢复数据
② 事故排查：某条消息 14:30 有问题
   → 按 14:25~14:35 时间窗口拉消息出来看
③ 审计 / 对账：查"某段时间内这个 topic 发了什么"
④ 补数据：下游漏处理了某段，只记得漏的时间段

为什么用时间不用 offset：
  人记不住"我要 offset=500 那条"，但记得住"昨天 0 点的消息"。
```

**三种定位方式对照：**

| 想按什么找 | 用哪个文件 |
|-----------|-----------|
| 按 offset 找消息 | .index（offset → .log 位置） |
| 按时间找消息 | .timeindex（时间 → offset）→ 再走 .index |
| 消息正文 | .log |

---

## 四、为什么 Kafka 的索引是"稀疏"的（和 RocketMQ 的核心区别）

```
RocketMQ ConsumeQueue：每条消息一条索引（稠密，20B/条）
  → offset × 20 直接算出精确位置，O(1)，一步到位
  代价：每条消息都要 20 字节索引，量大时索引本身也占不少空间

Kafka .index：每 4KB 才记一条（稀疏，8B/条）
  → 省空间：索引只有 RocketMQ 的零头
  代价：定位时只能找到"大概位置"，还要顺序往前扫几行到精确 offset
```

```
稀疏索引长这样（假设 baseOffset=300 的段）：
  .index:
    (相对0   → .log 位置 0)
    (相对100 → .log 位置 4096)     ← 每 4KB 记一条
    (相对200 → .log 位置 8192)
    (相对300 → .log 位置 12288)
  中间 offset=150 的消息没有专门索引
  → 想找 150：先落到 (100→4096)，再从 4096 字节处顺序扫到 150
```

---

## 五、一个拉取请求怎么一步步找到消息（完整时序）

**目标：消费者要 offset=500 那条消息。**

```
Consumer                              Broker（order-0 目录）
   │                                      │
   │ ① 从 __consumer_offsets 拿到         │
   │    该组该分区的消费位点 offset=500     │
   │                                      │
   │ ② Fetch 请求：                       │
   │    topic=order, partition=0,         │
   │    offset=500                        │
   │ ─────────────────────────────────→  │
   │                                      │ ③ 第一步：二分找【段】——看的是文件名，不是文件内容
   │                                      │    每个段的 .log / .index / .timeindex 三个文件
   │                                      │    共用同一个名字 = 该段第一条消息的 offset（baseOffset）
   │                                      │    如 000...300.log 的文件名就是 baseOffset=300
   │                                      │    内存里维护着文件名（baseOffset）的有序列表：
   │                                      │      [0, 300, 600, ...]
   │                                      │    找"最大的 baseOffset <= 500"
   │                                      │      → 300（因为 0≤500、300≤500、600>500）
   │                                      │      → 选中 000...300.log 这个段
   │                                      │    ★ 全程只比对文件名字，不打开 .log 读内容
   │                                      │
   │                                      │ ④ 第二步：二分找【段内 .index 近似位置】
   │                                      │    相对 offset = 500 - 300 = 200
   │                                      │    在 .index 里二分找"最大的相对 offset <= 200"
   │                                      │      → 比如找到 (相对200 → .log 位置 8192)
   │                                      │      → 拿到 .log 的大致字节位置 8192
   │                                      │
   │                                      │ ⑤ 第三步：顺序扫到精确 offset
   │                                      │    seek 到 .log 的 8192 字节处
   │                                      │    向前顺序读批次头（看 baseOffset/recordCount）
   │                                      │    扫到 offset=500 的精确位置
   │                                      │
   │                                      │ ⑥ 读出该条消息（连带所在 batch）
   │                                      │
   │ ⑦ 返回消息 + nextOffset=501         │
   │ ←───────────────────────────────── │
   │                                      │
   │ ⑧ 业务处理成功 → 提交 offset=501     │
   │ ─────────────────────────────────→  │ ⑨ 写回 __consumer_offsets
```

---

## 六、二分查找到底怎么"二分"（用数字讲透）

### 第一步：二分找段

```
内存里段列表（已按 baseOffset 排好序）：
  [0, 300, 600, 900, 1200]

要定位 offset=500：
  中间是 600，500 < 600 → 只看左半边 [0, 300]
  中间是 300，300 <= 500 → 候选段 baseOffset=300
  下一个段 600 > 500 → 确认：500 就在 baseOffset=300 的段里

本质：找"最后一个 <= 500 的 baseOffset"
```

### 第二步：二分找 .index 近似位置

```
该段 .index（相对 offset → .log 位置）：
  [(0 → 0), (100 → 4096), (200 → 8192), (300 → 12288)]

要定位相对 offset=200：
  二分找到"最大的相对 offset <= 200"
  → (200 → 8192)

如果相对 offset=250（索引里没有 250）：
  二分找到"最大的 <= 250" → (200 → 8192)
  → 从 8192 字节处开始顺序扫到 250
```

### 第三步：顺序扫（为什么只需要扫一点点）

```
从 .log 的 8192 字节处，读一个批次头：
  批次头里写了 baseOffset 和 recordCount
  → 这个 batch 是 offset 200~210 的 11 条
  → 扫到 offset=250？不在这个 batch，跳到下一个 batch 继续
  → 因为索引只差 4KB 数据量，最多扫几 KB 就到了
```

---

## 七、__consumer_offsets 到底是什么（消费位点存哪）

这是最容易晕的地方，单独拆开。

### 它不是一个文件，是一个【内部 topic】

```
__consumer_offsets = Kafka 内置 topic（默认 50 个分区）
  存：所有消费者组的消费位点

  和业务 topic 存储结构一模一样：
    __consumer_offsets-0/
      ├─ 000...000.log
      ├─ 000...000.index
      └─ 000...000.timeindex
    __consumer_offsets-1/ ...
```

### 里面存的是一条条"位点消息"

```
每条位点消息：
  key   = 消费者组 + topic + 分区（如 "order-group|order|0"）
  value = 该组消费到哪个 offset（如 501）

压缩 topic（log.cleanup.policy=compact）：
  同一个 key 只保留最新一条 value（老位点自动清理）
```

### 位点的读写时机

```
写：Consumer 提交 offset → GroupCoordinator 把新位点写进 __consumer_offsets
读：Consumer 启动 / rebalance → 从 __consumer_offsets 读该组最新位点
```

### 对比 RocketMQ

| | RocketMQ | Kafka |
|---|----------|-------|
| 位点存哪 | `config/consumerOffset.json` 文件 | `__consumer_offsets` 内部 topic |
| 形式 | 一个 json 文件 | 有 50 分区的 topic（.log/.index） |
| 读取 | Broker 直接读 json | 像读普通消息一样从 topic 拉 |

---

## 八、和 RocketMQ 整体对比（收束）

| | RocketMQ | Kafka |
|---|----------|-------|
| 正文 | CommitLog 全局混写 | 每分区独立 .log 段 |
| 分区目录 | 只是索引（consumequeue/） | 正文+索引都在分区目录 |
| 索引 | ConsumeQueue 20B/条，稠密 | .index 8B/条，稀疏（每4KB一条） |
| 定位 | offset×20 一步精确 O(1) | 二分找段 + 二分找近似位置 + 顺序扫 |
| 段/滚动 | CommitLog 1GB 滚文件 | 分区 .log 1GB 滚段 |
| 位点 | consumerOffset.json | __consumer_offsets topic |
| 设计取向 | 稠密索引换 O(1) | 稀疏索引省空间，换一点点扫描 |

---

## 九、一句话总结 + 面试话术

**一句话链条：**

```
__consumer_offsets 拿 offset
  → 二分找段（baseOffset）
  → 二分找 .index 近似位置（稀疏）
  → 顺序扫到精确 offset
  → 读 .log 正文
  → 消费成功提交 offset+1
```

**面试话术（30 秒）：**

> Kafka 每个分区一个独立目录，里面是分段存储——每段有 .log（正文）、.index（偏移索引）、.timeindex（时间索引），段文件名就是该段第一条消息的 baseOffset。找消息三步：先按 offset 二分找段（baseOffset <= offset 的最大段），再在段内 .index 里二分找稀疏索引得到 .log 的大致位置，最后顺序扫几 KB 到精确 offset。和 RocketMQ 比：RocketMQ 用稠密 ConsumeQueue 索引、offset×20 一步到位；Kafka 用稀疏索引省空间、代价是多两步查找。消费位点存在 __consumer_offsets 内部 topic（50 分区、压缩清理），不是单文件。
