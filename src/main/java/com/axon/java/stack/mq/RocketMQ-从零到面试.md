# RocketMQ 从零到面试 — 完整指南

> 适用：第一次接触 MQ 的初学者

---

## 第一章：RocketMQ 整体架构 — 三个角色一栋楼

### 1.1 用快递来理解

```
你想寄快递给朋友。

没有快递公司：你得自己跑去他家，敲门，递东西。如果人不在，白跑一趟。

有快递公司（RocketMQ）：
你（Producer）→ 把包裹交快递站 → 快递站帮你存着 → 朋友（Consumer）有空去取
                                 ↑
                     如果快递站太多，得有个
                     地址本（NameServer）告诉你哪个站收这个包裹
```

### 1.2 RocketMQ 四个角色

```
NameServer（地址本）──────── 记录：Topic=订单 → 哪个 Broker 负责
    │
    ├── Producer（寄件人）→ 查 NameServer → 发消息给 Broker
    │
    └── Consumer（收件人）→ 查 NameServer → 从 Broker 拿消息

Broker（快递站）→ 真实的存消息的地方
```

| 角色 | 比喻 | 功能 |
|------|------|------|
| **NameServer** | 地址本 | 记录"哪个 Topic 在哪个 Broker"，轻量无状态，多个共存 |
| **Broker** | 快递站 | 存储消息，主从架构。Master 负责收发，Slave 负责备份 |
| **Producer** | 寄件人 | 发消息到指定 Topic |
| **Consumer** | 收件人 | 从指定 Topic 拿消息消费 |

---

## 第二章：Topic / MessageQueue / ConsumerGroup — 核心三兄弟

### 2.1 比喻

```
Topic = 快递类型（订单件）
    ├─ MessageQueue-0  →  放在一号快递柜
    ├─ MessageQueue-1  →  放在二号快递柜
    ├─ MessageQueue-2  →  放在三号快递柜
    └─ MessageQueue-3  →  放在四号快递柜

ConsumerGroup-A = 取件小分队（订单处理队的）
    ├─ 小王 → 负责一号、二号柜子
    └─ 小李 → 负责三号、四号柜子

ConsumerGroup-B = 另一个取件大队（数据同步队的）
    └─ 自己能拿全部四个柜子的件
```

### 2.2 三条铁律

```
铁律 1：同一个 ConsumerGroup 内，每条消息只被一个 Consumer 消费。
         → 小王拿了一号柜的件，小李就不会重复拿。

铁律 2：不同 ConsumerGroup 各自独立消费。
         → A 组取完，B 组还能再取一遍，互不干扰。

铁律 3：一个 Queue 最多被组内一个 Consumer 消费。
         → 如果有 5 个 Consumer 但只有 4 个 Queue，第 5 个闲死。
```

### 2.3 顺序消息怎么来？

```
问题：订单 "先付款后发货"，不能反过来。

解法：同一个订单号的消息发到同一个 Queue。
     一个 Queue 内消息天然有序。
     该 Queue 由一个 Consumer 单线程顺序消费。

├─ Queue-0: [订单1-付款] → [订单1-发货]  ← 有序！
├─ Queue-1: [订单2-付款] → [订单2-发货]  ← 有序！
└─ Queue-2: [订单3-付款]                 ← 来啥处理啥
```

```java
// Producer 确保同一订单进同一 Queue
SendResult result = producer.send(
    new Message("order-topic", "order123", body),  // order123 是业务 ID
    (mqs, msg, arg) -> mqs.get(Math.abs(arg.hashCode()) % mqs.size()),
    "order123"   // 按 order123 hash → 同一 Queue
);
```

---

## 第三章：事务消息 — "先暂存，确定没事再发出去"

### 3.1 问题场景

```
用户下单：
① 扣库存（DB 操作）
② 发消息给积分系统（MQ 操作）

如果先发消息再扣库存 → 库存扣失败，消息已经发了，积分系统白白加积分。
如果先扣库存再发消息 → 库存扣了，消息没发出去，积分系统啥也没收到。
```

### 3.2 RocketMQ 的两阶段提交

```
第一阶段：预提交（Half Message）
   Producer 发一条半消息给 Broker → Broker 存着，但消费者看不到
   
第二阶段：执行本地事务
   Producer 执行扣库存（本地事务）
     ├─ 成功 → 发 Commit 给 Broker → 半消息变成正常消息，消费者可见
     └─ 失败 → 发 Rollback 给 Broker → 半消息被删除
     
兜底：Broker 长时间没收到 Commit/Rollback
   → Broker 主动问 Producer："你那个半消息的本地事务，到底成没成？"
   → Producer 回查本地事务状态 → Broker 决定 Commit 还是 Rollback
```

```
时间线：
Producer                   Broker                    Consumer
   │                         │                          │
   ├── 发半消息 ──────────→ │ 存半消息（消费者不可见）
   │                         │
   ├── 执行本地事务（扣库存）  │
   │   成功！                 │
   │                         │
   ├── 发 Commit ──────────→ │ 半消息 → 正常消息 ──→ Consumer 可见
   │                         │
```

---

## 第四章：消息不丢 — 三道防线

```
Producer ──send──→ Broker ──pull──→ Consumer
    ↑                ↑                ↑
  防线1            防线2            防线3
```

### 防线 1：Producer → Broker

```java
// 同步发送 + 重试
producer.send(message, new SendCallback() {
    public void onSuccess(SendResult result) { /* 成功了 */ }
    public void onException(Throwable e) { /* 重试或记日志 */ }
});
```

### 防线 2：Broker 磁盘

```properties
# Broker 配置文件
flushDiskType = SYNC_FLUSH      # 同步刷盘 — 消息写到磁盘才返回成功
brokerRole = SYNC_MASTER        # 主从同步双写 — Slave 也写成功才返回
```

### 防线 3：Broker → Consumer

```java
consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
    for (MessageExt msg : msgs) {
        bizProcess(msg);         // 处理业务
    }
    return ConsumeOrderlyStatus.SUCCESS;  // 只有处理成功才确认
});
```

---

## 第五章：消息积压和幂等

### 5.1 消息堆了几百万条怎么办？

```
原因分析：Consumer 处理能力跟不上 Producer 发送速度。

解决方案（由快到慢）：
① 扩容 Consumer：增加 Consumer 实例 = Queue 数
② 降级处理：新 Consumer 只记日志不处理，先把消息"消费掉"
③ 迁移 Topic：积压消息灌到新 Topic，开 20 个 Queue，20 个临时 Consumer 并行消化
④ 排查原因：Consumer 里是否有慢 SQL、锁等待
```

### 5.2 消息重复了怎么办？（幂等）

**为什么消息会重复？**
> Producer 发完没 → 两条一样消息。
> Consumer 消费一半宕机了，Broker 没收到确认 → 重发给另一个 Consumer。

**解决方案：业务唯一 ID**

```java
public void handleOrderMessage(OrderMessage msg) {
    String orderId = msg.getOrderId();
    
    // 查 Redis：这个 orderId 是不是处理过了？
    if (redis.setNx("order:processed:" + orderId, "1", 60, TimeUnit.SECONDS)) {
        // 没处理过 → 处理
        processOrder(msg);
    } else {
        // 处理过了 → 直接跳过
        log.info("重复消息，跳过: " + orderId);
    }
}
```

| 方案 | 做法 |
|------|------|
| **Redis setNx** | key=业务ID，抢到锁才处理，过期自动删 |
| **DB 唯一键** | insert 时唯一键冲突 → 已处理过 |
| **业务状态** | 查订单状态，已处理过的跳过 |

---

## 第六章：面试速记

### 6.1 一句话

> **RocketMQ 是 Java 写的分布式消息队列，阿里出品。NameServer 做注册中心，Broker 主从存储，原生支持顺序消息和分布式事务。**

### 6.2 事务消息怎么回答？

> 两阶段：先发半消息（消费者不可见），再执行本地事务。成功就 Commit，失败就 Rollback。长时间未决 → Broker 反查生产者。

### 6.3 顺序消息怎么保证？

> 同一业务 key 发到同一 MessageQueue，Queue 内有序。一个 Consumer 单线程顺序消费该 Queue。

### 6.4 消息不丢？

> 三道防线：生产端同步发送+重试、Broker 同步刷盘+主从同步、消费端业务处理成功再确认。

### 6.5 幂等怎么搞？

> 业务唯一 ID + Redis setNx 判重。处理过的直接跳过。

---

## 第七章：关键词中英文对照

| 中文 | 英文 |
|------|------|
| 名称服务器 | NameServer |
| 消息队列 | MessageQueue |
| 消费者组 | ConsumerGroup |
| 主题 | Topic |
| 半消息 | Half Message |
| 事务消息 | Transactional Message |
| 幂等 | Idempotent |
| 刷盘 | Flush |
| 主从同步 | Master-Slave |
