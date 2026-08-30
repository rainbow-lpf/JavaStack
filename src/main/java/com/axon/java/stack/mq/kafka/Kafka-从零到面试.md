# Kafka 从零到面试 — 完整指南

> 适用：第一次接触 MQ 的初学者

---

## 第一章：Kafka 是什么？用"日志"来理解

### 1.1 不要想成"消息队列"，想成"日志"

```
Kafka 的名字来自作家卡夫卡（Kafka），但理解它最好的比喻是——流水账本。

你见过超市收银机吗？
每一笔交易都先记到一个小本本上，按顺序一行一行往下写。
不删、不改、只追加到最后面。

Kafka 就是这样一个巨大的流水账本。
所有消息按顺序追加写到文件末尾，永不删除（有有效期的"永不"）。
```

### 1.2 Kafka 和 RabbitMQ 的区别（一句话）

```
RabbitMQ：像邮箱。消息投进信箱，收件人取走 → 删掉。
Kafka：   像账本。记录写下来，想看随时翻回去看。
```

---

## 第二章：Kafka 的四个核心概念

### 2.1 用"电视台"理解

```
Topic（频道）= CCTV-5 体育频道
Partition（子频道）= CCTV-5 一频道、CCTV-5 二频道、CCTV-5 三频道
    有了子频道，同一时间能播三场不同比赛，并行处理！
Producer（节目制作组）= 把内容送到频道上
Consumer（观众群）= 选一个频道看
    同一个观众群内，小张看一频道，小李看二频道 → 各看各不重复
    不同观众群可以都看一个频道
```

### 2.2 四个角色

| 概念 | 比喻 | 作用 |
|------|------|------|
| **Topic** | 电视频道 | 消息分类，逻辑概念 |
| **Partition** | 子频道 | 物理分区，真正的消息存储单元，每个是一串有序文件 |
| **Producer** | 节目组 | 发消息 |
| **Consumer Group** | 观众群 | 组内每人看不同子频道，一条消息组内只被一个人看 |

### 2.3 核心铁律

```
铁律 1：一个 Partition 在同一个 ConsumerGroup 内，只能被一个 Consumer 消费。
         → Consumer 数 ≤ Partition 数，多了的 Consumer 闲逛。

铁律 2：不同 ConsumerGroup 各自独立消费。
         → Group-A 看完，Group-B 还能看。

铁律 3：一个 Partition 内消息严格有序。
         → 同一个业务 ID 发到同一 Partition → 天然有序。
```

### 2.4 Kafka 集群全景架构图

```
                         ┌───────────────────────────────────────────┐
                         │       Zookeeper / KRaft（元数据中心）        │
                         │   存储：Broker 列表、Controller 选举、       │
                         │        Topic 配置、Partition 分布           │
                         └───────────────────────────────────────────┘
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
                    ▼                      ▼                      ▼
           ┌──────────────┐       ┌──────────────┐       ┌──────────────┐
           │  Broker-1    │       │  Broker-2    │       │  Broker-3    │
           │              │       │              │       │              │
           │ P0 (Leader)  │       │ P0 (Follower)│       │ P2 (Leader)  │
           │ P1 (Follower)│       │ P1 (Leader)  │       │ P2 (Follower)│
           │ P3 (Follower)│       │ P3 (Leader)  │       │ P4 (Follower)│
           │ P4 (Leader)  │       │ P5 (Follower)│       │ P5 (Leader)  │
           └──────┬───────┘       └──────┬───────┘       └──────┬───────┘
                  │                      │                      │
                  │         读写只走 Leader！                    │
                  └──────────────────────┼──────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
          ┌──────────────────┐                      ┌──────────────────┐
          │   Consumer Group │                      │   Consumer Group │
          │   "订单处理组"    │                      │   "数据同步组"    │
          │                  │                      │                  │
          │ Consumer-1: P0   │                      │ Consumer-4: P0   │
          │ Consumer-2: P1   │                      │ Consumer-5: P1   │
          │ Consumer-3: P2   │                      │ Consumer-6: P2   │
          └──────────────────┘                      └──────────────────┘
              ↑ 不同组各自独立消费 ↑
```

**读懂这张图：**

```
① 3 台 Broker（物理机器），Topic 有 6 个 Partition（P0~P5）
② 每个 Partition 有 3 个副本分布在 3 台机器上（Leader×1 + Follower×2）
   → P0：Broker-1 是 Leader（负责读写），Broker-2 是 Follower（备份）
③ Producer 发消息，只发到 Leader 副本
   → P0 的消息写到 Broker-1（Leader），自动同步给 Broker-2（Follower）
④ Consumer-1 负责 P0 → 从 Broker-1 的 P0 Leader 拉消息
⑤ "订单处理组"和"数据同步组"各自独立消费，互不干扰
```

---

### 2.5 示例：3 个消费者怎么分配 4 个分区

```
Partition-0 ─────→ Consumer-1
Partition-1 ─────→ Consumer-1   ← 一个人分到两个分区
Partition-2 ─────→ Consumer-2
Partition-3 ─────→ Consumer-3

如果再加一个 Consumer-4 → 闲着，没分区可分。

如果 Consumer-2 死了 → Partition-2 和 Partition-3 重新分给剩下的人。
这叫 Rebalance（重平衡）。
```

---

## 第三章：Kafka 为什么这么快？

### 3.1 四个加速引擎

| 机制 | 普通人做法 | Kafka 做法 | 为什么快 |
|------|----------|----------|------|
| **顺序写** | 随便写磁盘，磁头到处跑 | 追加到文件末尾，磁头顺着往下写 | 顺序写速度 ≈ 内存随机写 |
| **零拷贝** | 磁盘→用户态→内核态→网卡，倒来倒去 | `sendfile()` 磁盘→内核态直接→网卡，不经过用户态 | 省两次复制 |
| **PageCache** | 自己搞缓存 | 直接用 OS 的 PageCache，读写都走内存 | 不重复造轮子 |
| **批量** | 一条条发一条条收 | 攒一批一起发、攒一批一起收 | 减少网络请求次数 |

### 3.2 一个通俗的解释

```
你去火锅店点菜：

普通做法（随机写磁盘）：
  "服务员，再来一份毛肚" → 服务员跑去后厨下单
  "服务员，再来一份鸭血" → 服务员再跑去后厨下单
  一趟一趟跑。

Kafka 做法（顺序写 + 批量）：
  服务员手机记：毛肚×1、鸭血×1、虾滑×1...
  攒够了，一趟到后厨，一次性全下单。
```

---

## 第四章：Kafka 消息存储与回溯

### 4.1 消息存在哪？

```
Topic: order
 ├─ Partition-0
 │     ├─ 00000000000000000000.log   ← 消息数据文件
 │     ├─ 00000000000000000000.index ← 索引文件（根据 offset 快速定位到 log 文件位置）
 │     ├─ 00000000000000000000.timeindex ← 时间索引（根据时间快速定位）
 │     └─ ...
 └─ Partition-1
       └─ ...
```

### 4.2 Offset 是什么？

```
Offset 就是消息的编号，从 0 开始递增，只增不减。

Partition-0:
offset=0: "订单创建"     ← 第 1 条
offset=1: "订单支付"     ← 第 2 条
offset=2: "订单发货"     ← 第 3 条
offset=3: "订单完成"     ← 第 4 条
```

### 4.2.1 深入：一条消息到底怎么定位（对比 RocketMQ）

先看 RocketMQ 的寻址链（已讲过）：

```
consumerOffset.json 拿 offset → offset×20 定位 ConsumeQueue 索引
→ 索引里 offsetPy + sizePy → 去 CommitLog 读正文
```

Kafka 是同一套思想，但**索引是稀疏的**，所以多了一步"近似定位 + 顺序扫"。

**存储：每个分区独立目录，三件套**

```
Topic: order
 └─ Partition-0/
     ├─ 00000000000000000000.log        ← 段日志：消息正文，append-only
     ├─ 00000000000000000000.index      ← 偏移索引：offset → .log 物理位置
     ├─ 00000000000000000000.timeindex  ← 时间索引：时间戳 → offset
     └─ 00000000001073741824.log...     ← 写满 1GB 滚下一个段

文件名 = 该段的起始 offset（baseOffset）
```

**关键区别：Kafka 索引是"稀疏"的**

```
RocketMQ ConsumeQueue：每条消息一条索引，稠密
  → offset × 20 一步精确命中 O(1)

Kafka .index：不每条都记，默认每攒 4KB（log.index.interval.bytes）才记一条
  每条索引 8 字节 = 4B 相对 offset + 4B .log 物理位置
  → 稀疏省空间，但只能定位到"近似位置"，再顺序扫几行到精确 offset
```

**完整读取流程**

```
① 消费位点存 __consumer_offsets 内部 topic（不是 json 文件）
   消费者组 → 各分区的 offset

② Consumer 发 Fetch 请求：topic + partition + offset=500

③ Broker 定位段：二分查找所有 .log 文件名（baseOffset）
   找到 baseOffset <= 500 < 下一个 baseOffset 的那个段

④ 段内查 .index：二分找"最大的 <= (500 - baseOffset)"的稀疏条目
   → 拿到 .log 里的大致物理位置

⑤ seek 到该位置，顺序往前扫到 offset=500 → 读出消息 batch

⑥ 返回给消费者
```

**时序图：一次拉取的全过程**

```
Consumer                              Broker（Partition-0 磁盘）
   │                                      │
   │ ① 从 __consumer_offsets 拿到         │
   │    该组该分区的 offset=500            │
   │                                      │
   │ ② Fetch 请求：                       │
   │    topic=order, partition=0,         │
   │    offset=500                        │
   │ ─────────────────────────────────→  │
   │                                      │ ③ 定位段（二分找文件名 baseOffset）
   │                                      │    ├─ 000...000.log  baseOffset=0
   │                                      │    ├─ 000...300.log  baseOffset=300   ← 500 在这段
   │                                      │    └─ 000...600.log  baseOffset=600
   │                                      │    （300 <= 500 < 600 → 选 baseOffset=300 的段）
   │                                      │
   │                                      │ ④ 查该段 .index（二分，稀疏）
   │                                      │    相对 offset = 500 - 300 = 200
   │                                      │    .index 里最接近 200 的条目
   │                                      │    → 拿到 .log 的大致物理位置
   │                                      │
   │                                      │ ⑤ seek 到该位置，顺序向前扫
   │                                      │    扫到 offset=500 的精确位置
   │                                      │    → 读出这条消息（及其所在 batch）
   │                                      │
   │ ⑥ 返回消息 + nextOffset=501         │
   │ ←───────────────────────────────── │
   │                                      │
   │ ⑦ 业务处理成功 → 提交 offset=501     │
   │ ─────────────────────────────────→  │ ⑧ 写回 __consumer_offsets
   │                                      │    （异步定期提交）
```

**一句话链条（对应 RocketMQ 那句话）：**

```
__consumer_offsets 的 offset（第几条）
  → 二分定位段（baseOffset）
  → 二分定位 .index 近似位置（稀疏）
  → 顺序扫到精确 offset → 读 .log 正文
  → 消费成功 → 提交 offset+1
```

两次二分 + 一小段顺序扫；对比 RocketMQ 的"×20 一步到位"，Kafka 用**稀疏索引省空间，代价是多两步查找**。

**对比速记**

| | RocketMQ | Kafka |
|---|----------|-------|
| 正文 | CommitLog 全局混写 | 每分区独立 .log 段 |
| 索引 | ConsumeQueue 20B/条，稠密 | .index 8B/条，稀疏（每4KB一条） |
| 定位 | offset×20 一步精确 O(1) | 二分找段 + 二分找近似位置 + 顺序扫到精确 |
| 位点存哪 | consumerOffset.json | __consumer_offsets 内部 topic |
| 设计取向 | 定长稠密索引换 O(1) | 稀疏索引省空间，换一点点扫描 |

### 4.3 消息回溯 — 这是 Kafka 的绝活

```
RabbitMQ：消息被确认就删了，想看以前的？没了。
Kafka：   消息按时间保留（默认 7 天），只要没过期，想回溯哪段就哪段。

比如：今天上线的新功能有 bug，把数据搞坏了。
     → 把所有消费者 offset 重置到昨天凌晨 0 点
     → 重新消费一遍昨天的消息
     → 数据恢复！
```

```bash
# 把 Consumer Group 的 offset 重置到某个时间
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group order-group \
  --topic order-topic \
  --reset-offsets \
  --to-datetime 2024-01-01T00:00:00.000 \
  --execute
```

---

## 第五章：消息不丢（可靠性）

### 5.1 Kafka 的消息可靠性靠 ISR

```
ISR = In-Sync Replicas = 跟 Leader 保持同步的副本集合

Partition-0:
  Leader（主）：负责读写
  Follower-1（从-1）：数据差了 10 条 → 不在 ISR
  Follower-2（从-2）：数据只差 1 条 → 在 ISR ✅
```

### 5.2 TS 不丢配置三件套

```properties
# ① Producer acks = all（发消息等所有 ISR 确认才返回成功）
acks=all
retries=3

# ② Broker 副本数 ≥ 3，最小 ISR 数 ≥ 2
replication.factor=3
min.insync.replicas=2

# ③ Consumer 手动提交 offset，处理完再提交
enable.auto.commit=false
```

```
消息发送流程：
Producer → Partition-0 Leader → 写入本地
                              → 同步给 ISR 里的 Follower
                              → 所有 ISR 都确认了
                              → 返回成功给 Producer
```

### 5.3 丢 vs 重复？Kafka 的选择

```
Kafka 默认：At Least Once（至少一次）

向：消息可能重复，但绝不丢。

如果要 "恰好一次"（Exactly Once）：
  消费端自己搞幂等 ↓
```

---

## 第六章：消息重复 & 幂等

### 6.1 为什么 Kafka 的消息可能重复？

```
Producer 发了消息 → Broker 存好了 → 回 ack → 网络断了 → Producer 没收到
                               → Producer 重发 → 同一条消息存了两遍

Consumer 消费了 3 条 → offset=0,1,2 → 提交 offset=3 → 服务挂了，还没提交成功
                    → 恢复后从 offset=2 重新消费 → 第 3 条消息重复消费了
```

### 6.2 幂等生产者（Producer 端去重）

```properties
enable.idempotence=true
# Producer 给每条消息带 ProducerId + SequenceNumber
# Broker 看到同 ProducerId + 同 SequenceNumber → 直接丢弃
```

### 6.3 消费者端幂等（消费端去重）

```java
// 和 RocketMQ 一样：业务唯一 ID
String bizId = extractBizId(message);
if (redis.setNx("kafka:processed:" + bizId, "1", 60, TimeUnit.SECONDS)) {
    processMessage(message);  // 没处理过 → 处理
} else {
    // 重复消息，跳过
}
```

---

## 第七章：Kafka 的应用场景

| 场景 | 说明 |
|------|------|
| **日志采集** | 所有服务的日志 → Kafka → ElasticSearch |
| **用户行为追踪** | 点击流 → Kafka → 实时计算 / 推荐引擎 |
| **数据管道** | MySQL Binlog → Kafka → Hadoop/Spark |
| **流计算** | Kafka → Flink/Spark Streaming → 实时大屏 |
| **消息队列** | 业务异步解耦（订单/短信/邮件） |

---

## 第八章：面试速记

### 8.1 一句话说清 Kafka

> **Kafka 是分布式流平台，本质是一个巨大的 append-only 日志。消息按 Topic 分类、按 Partition 分片，顺序写磁盘 + 零拷贝 + PageCache 实现百万级吞吐。**

### 8.2 为什么 Kafka 这么快？

> 四个加速：① 顺序写磁盘（append-only，磁头不寻道）、② 零拷贝 `sendfile()`（磁盘直接到网卡，不经过用户态）、③ PageCache（读写走 OS 缓存）、④ 批量读写（攒一批再传）。

### 8.3 消息不丢怎么配？

> Producer：`acks=all` + 重试。Broker：副本 ≥ 3 + ISR ≥ 2。Consumer：手动提交 offset，处理完再提交。

### 8.4 Kafka 和 RabbitMQ 有什么区别？

> RabbitMQ：消息确认就删，微秒延迟，复杂路由。Kafka：消息不删，按 offset 可回溯，百万吞吐。RabbitMQ 像邮箱，Kafka 像账本。

### 8.5 顺序消息怎么保证？

> 同 key 进同一 Partition，Partition 内有序。一个 Consumer 消费一个 Partition，单线程消费勿并发。

### 8.6 重复消费怎么弄？

> ① 幂等生产者 `enable.idempotence=true`。② 消费端业务唯一 ID + Redis/DB 判重。

---

## 第九章：关键词中英文对照

| 中文 | 英文 |
|------|------|
| 主题 | Topic |
| 分区 | Partition |
| 消费者组 | Consumer Group |
| 偏移量 | Offset |
| 副本 | Replica |
| 领导者 | Leader |
| 跟随者 | Follower |
| 同步副本集 | ISR (In-Sync Replicas) |
| 重平衡 | Rebalance |
| 零拷贝 | Zero Copy |
| 幂等 | Idempotent |
| 回溯 | Rewind / Reset |
