# Kafka 消息类型与能力对照

> 纠偏：Kafka 也是“日志”思维，没有 RocketMQ 式内建类型；但事务消息是真的有

---

## 一、概念纠偏

```
RocketMQ：Broker 内建类型（普通/顺序/事务/延迟/批量）
RabbitMQ：AMQP 属性 + 组合技巧，事务没有，顺序靠模拟
Kafka   ：日志思维，能力来自【分区 + 副本 + 幂等生产者 + 事务协调器】

Kafka 的特点：
  ① 事务消息 —— 真的有（幂等生产者 + Transaction Coordinator），比 RabbitMQ 强
  ② 顺序消息 —— 分区内有序（比 RocketMQ 的"分区顺序"同思路）
  ③ 延迟消息 —— 没有原生（要自己实现：定时轮询 / 消息带时间戳消费端延迟处理）
  ④ 批量消息 —— 这是核心机制，不是可选优化（攒批是吞吐来源）
```

---

## 二、与 RocketMQ / RabbitMQ 三方对照（核心）

| 能力 | RocketMQ | RabbitMQ | Kafka |
|------|----------|----------|-------|
| 普通消息 | ✅ | ✅ | ✅ |
| 顺序消息 | ✅ 分区顺序（key→队列） | ⚠️ 单队列单消费者模拟 | ✅ **分区内有序**（key→partition） |
| 事务消息 | ✅ 半消息+回查 | ❌ 只有笨重 tx | ✅ **幂等生产者 + 事务协调器** |
| 延迟消息 | ✅ 18级 / 5.x 任意 | ✅ TTL+死信 / 插件 | ❌ 无原生 |
| 批量消息 | ⚠️ 可选优化 | ⚠️ 自己攒批 | ✅ **核心机制（攒批）** |
| 消息过滤 | ✅ tagHash / SQL | ✅ 交换机路由 | ❌ 无 Broker 端过滤，全靠消费端 |
| 消息回溯 | ✅ offset 拨回 | ❌ 消费即删 | ✅ **原生绝活**（offset 重置） |

**记忆：Kafka 强在批量吞吐 + 分区顺序 + 事务 + 回溯；弱在延迟消息（无原生）和 Broker 端过滤（无）。**

---

## 三、Kafka 的事务消息：和 RocketMQ 完全不同的思路

```
RocketMQ 事务：解决"本地事务 + 消息发送"的原子性（半消息 + 回查）

Kafka 事务：解决"多分区原子写入 + 流处理恰好一次"
  → 幂等生产者（ProducerId + SequenceNumber）防单分区重复
  → 事务协调器（Transaction Coordinator）协调跨分区原子提交
  → 典型场景：consume-transform-produce（读A→处理→写B 要原子）
```

详见《Kafka-事务消息原理.md》。

---

## 四、Kafka 的顺序消息：分区内有序

```
一个分区内，消息按追加顺序有序；跨分区无顺序
→ 同一 key 的消息 hash 到同一分区 → key 内有序

和 RocketMQ 分区顺序消息同思路：
  Kafka    key → partition
  RocketMQ key → MessageQueue
```

```
Producer：key 相同 → 同一分区（默认 key.hashCode % 分区数）
Consumer：单分区内单线程顺序消费
风险   ：Rebalance 时分区重新分配，消费可能重复 → 靠幂等兜底
```

详见《Kafka-顺序消息原理.md》。

---

## 五、Kafka 的延迟消息：没有原生，三种替代

```
① 消息带"可消费时间戳"，消费者拉到时检查：
     没到时间 → 丢到本地延迟队列/暂停消费，到点再处理
     （消费端实现，Broker 无感知）

② 单层时间轮 + 多级延迟 Topic：
     延迟1min的消息先进 delay-1m Topic，定时任务到点转发到真实 Topic
     （参考 RocketMQ 思路自己造轮子）

③ 直接用 RocketMQ 的延迟消息 / RabbitMQ 延迟插件
     —— Kafka 本职是流，不是做延迟调度的
```

**结论：需要延迟消息就别硬用 Kafka，换 RocketMQ/RabbitMQ。**

---

## 六、面试话术（30 秒）

> Kafka 是日志思维，没有 RocketMQ 那种内建消息类型。能力对照：顺序靠分区内有序——同一 key hash 到同一分区；事务是真的有，靠幂等生产者加事务协调器，解决多分区原子写入和流处理恰好一次，但和 RocketMQ 半消息回查是两套思路；延迟消息没有原生，要么消费端按时间戳延迟处理，要么自己造多级延迟 Topic 转发，一般建议直接用 RocketMQ；批量是 Kafka 的核心机制不是可选优化，攒批就是它吞吐的来源；消息回溯是它的绝活——offset 拨回去重放。一句话：Kafka 强在吞吐、分区顺序、事务、回溯，弱在延迟和 Broker 端过滤。
