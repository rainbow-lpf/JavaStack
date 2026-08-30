# Kafka 事务消息原理

> 幂等生产者 + 事务协调器：Kafka 的"恰好一次"怎么做到

---

## 一、和 RocketMQ 事务消息的本质区别

```
RocketMQ 事务：解决"本地事务 + 消息发送"的原子性
  半消息 → 本地事务 → Commit/Rollback → 回查兜底

Kafka 事务：解决"多分区原子写入 + 流处理恰好一次"
  幂等生产者 + 事务协调器
  → 场景：consume-transform-produce（读 A 分区 → 处理 → 写 B 分区，要么都成要么都不成）
```

**Kafka 事务的初心不是"订单和消息原子"，而是"流处理里的原子性"。**

> **两者的"事务"同名不同义，不能互相当替代品：RocketMQ 事务管业务最终一致，Kafka 事务管消息原子写。**
> 所以对比只能比"差异"，不能比"优劣"——它们解决的不是同一个问题。

---

## 二、两个前置组件

### 1. 幂等生产者（单分区不重复）

```
每条消息带：ProducerId + SequenceNumber（递增）
Broker 端记录每个 ProducerId 已收到的最大 SequenceNumber
  → 收到重复的（<= 已记录）→ 直接丢弃
  → 解决"发送重试导致的单分区重复"

配置：enable.idempotence=true
```

### 2. 事务协调器（Transaction Coordinator）

```
一个 Broker 上跑的事务协调器，负责：
  - 分配事务 ID（transactional.id）
  - 管理事务状态（PrepareCommit / CompleteCommit / Abort）
  - 把事务结果以消息形式写进内部 __transaction_state 主题
```

---

## 三、事务流程（consume-transform-produce）

```
① initTransactions()：注册事务，协调器分配 ProducerId + Epoch
② beginTransaction()：开启事务
③ 消费 A 分区消息 + 处理 + 发送消息到 B 分区（都挂在当前事务下）
④ commitTransaction()：
     写 PrepareCommit 标记到 __transaction_state
     → 所有参与分区写 Commit 标记
     → 消费者只消费已 Commit 的消息
⑤ 中途失败 → abortTransaction()：所有分区的未提交消息标记 Abort，消费者跳过
```

---

## 四、"恰好一次"的消费语义

```
消息在 B 分区里带两个标记：
  - 未 Commit 的消息：普通消费者拉不到（被过滤）
  - 只读已提交（read_committed）消费者：默认，只看 Commit 的

消费端配合：
  isolation.level = read_committed  ← 事务消息才可见
  isolation.level = read_uncommitted ← 未提交也可见（默认老行为）
```

**关键：Kafka 的"恰好一次"= 幂等生产者（不重复写）+ 事务标记（读端过滤未提交）+ 消费端手动提交 offset 的原子性。**

---

## 五、性能代价（面试必问）

```
事务消息吞吐只有非事务的 30%~50%：
  - 每次提交要写 __transaction_state 协调消息
  - 多一轮网络往返
  - 消费者过滤未提交消息有开销

所以：只有真正需要原子性/恰好一次时才开，常规消息不用事务
```

---

## 六、和 RocketMQ 事务消息对比表

| | RocketMQ | Kafka |
|---|----------|-------|
| 解决目标 | 本地事务 ↔ 消息发送原子 | 多分区原子写 + 流处理恰好一次 |
| 机制 | 半消息 + 回查 | 幂等生产者 + 事务协调器 |
| 兜底 | Broker 反查 Producer | 协调器状态持久化在 __transaction_state |
| 典型场景 | 下单扣库存发积分 | 读A流处理写B流 |
| 性能 | 有开销但可接受 | 吞吐掉 50%+ |

---

## 七、面试话术（30 秒）

> Kafka 事务和 RocketMQ 思路完全不同：RocketMQ 解决"本地事务和消息发送的原子"，靠半消息加回查；Kafka 解决"多分区原子写入和流处理恰好一次"，靠两件套——幂等生产者（ProducerId + SequenceNumber 防单分区重复）加事务协调器（管理事务状态、写 __transaction_state 主题）。流程：initTransactions → begin → 消费处理再发送 → commit，所有分区一起打 Commit 标记，消费端用 read_committed 隔离级别只看已提交消息。代价是吞吐掉一半，所以只在该用原子性的场景开。一句话：RocketMQ 事务管"发不发"，Kafka 事务管"一批写原子不原子"。
