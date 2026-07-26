# MQ 消息队列 — 面试总结

> 目录：`com.axon.java.stack.mq`

---

## 一、三种 MQ 横向对比

| 维度 | RabbitMQ | RocketMQ | Kafka |
|------|---------|---------|------|
| **开发语言** | Erlang | Java | Scala/Java |
| **协议** | AMQP、MQTT、STOMP | 自定义 TCP 协议 | 自定义 TCP 协议 |
| **消息模型** | Exchange → Queue → Consumer | Topic → Queue → Consumer | Topic → Partition → Consumer |
| **吞吐量** | 万级 | **十万级** | **百万级** |
| **延迟** | 微秒级 | 毫秒级 | 毫秒级 |
| **顺序消息** | ❌ 不原生支持 | ✅ 原生支持 | ✅ 分区内有序 |
| **事务消息** | 支持（性能差） | ✅ 两阶段提交，性能好 | ❌ |
| **消息回溯** | ❌ | ✅ 按时间/偏移量回溯 | ✅ 按偏移量回溯 |
| **高可用** | 镜像队列 | 主从 + 自动切换 | 分区副本 + ISR |
| **适用场景** | 复杂路由、金融支付 | 电商、金融、分布式事务 | 大数据、日志、流处理 |

---

## 二、RabbitMQ

### 2.1 核心架构

```
Producer → Exchange → [Binding, RoutingKey] → Queue → Consumer
```

| 概念 | 作用 |
|------|------|
| **Exchange** | 交换机，接收消息，按路由规则分发 |
| **Queue** | 队列，存消息 |
| **Binding** | 绑定关系，Exchange 和 Queue 之间的桥 |
| **RoutingKey** | 路由键，决定消息丢到哪个 Queue |

### 2.2 消息确认机制

| 机制 | 说明 |
|------|------|
| **生产者 Confirm** | 消息到达 Broker → 回 ack；失败 → nack |
| **生产者 Return** | 消息无法路由到队列 → 回调 ReturnListener |
| **消费者 ack** | 手动确认，`basicAck` 成功，`basicNack/reject` 失败 |
| **自动确认** | 发到消费者立即删除，高风险 |

### 2.3 死信队列（DLQ）

**三种情况消息变死信：**

1. 消费者 `basic.reject/basic.nack` 且 `requeue=false`
2. 消息 TTL 过期
3. 队列满

```java
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "dlx-exchange");
args.put("x-message-ttl", 60000);           // 队列级 TTL 60s
channel.queueDeclare("myQueue", true, false, false, args);
```

**用途：** 延迟消息、故障排查、消息重试、监控报警。

---

## 三、RocketMQ

### 3.1 核心架构

```
NameServer（无状态注册中心，去中心化）
    │
    ├── Broker Master ──同步── Broker Slave
    │
    ├── Producer（发消息，负载均衡选 Broker/Queue）
    │
    └── Consumer（拉消息，集群消费 / 广播消费）
```

### 3.2 Topic / Group / MessageQueue 关系

```
Topic（订单主题）
  ├─ MessageQueue-0  →  Broker-1
  ├─ MessageQueue-1  →  Broker-1
  ├─ MessageQueue-2  →  Broker-Queue-3  →  Broker-2

ConsumerGroup-A（订单消费者组）
  ├─ Consumer-1 拉 Queue-0, Queue-1
  └─ Consumer-2 拉 Queue-2, Queue-3

同一 Group 内，每条消息只被一个 Consumer 消费。
不同 Group 各自独立消费同一 Topic 的消息。
```

### 3.3 事务消息（两阶段提交）

```
① Producer 发半消息（Half Message）→ Broker，消费者不可见
② 执行本地事务
   ├─ 成功 → Broker Commit → 消息对消费者可见
   └─ 失败 → Broker Rollback → 丢弃半消息
③ 长时间未 Commit/Rollback → Broker 反查 Producer 本地事务状态
```

> **RocketMQ 事务消息高性能关键：** 半消息顺序写磁盘、异步确认非阻塞、批量操作、事务检查延迟异步。

### 3.4 顺序消息

**原理：** 同一业务 ID（如订单号）的消息发到同一个 MessageQueue，Consumer 单线程顺序消费该 Queue。

### 3.5 消息幂等

| 方案 | 说明 |
|------|------|
| **业务唯一 ID** | 消费前查 Redis/DB 是否已处理过 |
| **数据库唯一键** | insert 时唯一键冲突 → 已处理 |
| **Redis setNx** | `setNx orderId:123` → 抢到才处理 |

---

## 四、Kafka

### 4.1 核心架构

```
Producer → Topic → [Partition-0 (Leader) → Replica-0, Replica-1]
               → [Partition-1 (Leader) → Replica-0, Replica-1]
               → [Partition-2 (Leader) → Replica-0, Replica-1]
                                                          │
Consumer Group → Consumer-1 拉 Partition-0, Partition-1
              → Consumer-2 拉 Partition-2
                                                          │
Zookeeper/KRaft → 管理 Broker、Controller 选举、元数据
```

| 概念 | 作用 |
|------|------|
| **Broker** | Kafka 服务节点，存储消息 |
| **Topic** | 消息分类，逻辑概念 |
| **Partition** | 物理分区，每个 Partition 是一个有序队列，存消息 |
| **Leader/Follower** | 每个 Partition 有一个 Leader 副本负责读写，Follower 异步同步 |
| **ISR** | In-Sync Replicas——与 Leader 保持同步的副本集合 |
| **Consumer Group** | 组内每个 Consumer 消费不同 Partition（一条消息只被组内一个 Consumer 消费） |
| **Controller** | Broker 集群中一个特殊节点，负责 Partition 选举、副本管理等 |

### 4.2 高吞吐原因

| 机制 | 说明 |
|------|------|
| **顺序写磁盘** | Append-only，磁盘顺序写速度接近内存随机写 |
| **零拷贝** | `sendfile()` 系统调用，数据从磁盘 → PageCache → 网卡，不经过用户态 |
| **Page Cache** | 消息先写 OS 的 PageCache，读写都走内存 |
| **分区并行** | 多 Partition 多台 Broker 并行处理 |
| **批量读写** | Producer 批量发，Consumer 批量拉 |

### 4.3 消费模型

```
Topic: order-topic（3 个 Partition）

Consumer Group A（订单处理组）
  Consumer-1 → Partition-0, Partition-1
  Consumer-2 → Partition-2
  同组内一条消息只被一个 Consumer 消费 ✅

Consumer Group B（数据同步组）
  Consumer-3 → Partition-0, Partition-1, Partition-2
  不同组各自独立消费 ✅
```

**关键规则：** 同一个 Consumer Group 内，一个 Partition 最多被一个 Consumer 消费。Consumer 数量 > Partition 数时，多出的 Consumer 空转。

### 4.4 Offset 与消息回溯

```java
// 每个 Consumer 有自己的 offset
Consumer Group A → Partition-0 → offset = 156
                                 → offset = 157（当前）
                                 → offset = 139（重置后回退到 139）

// 手动重置 offset
kafka-consumer-groups --reset-offsets --to-datetime 2024-01-01T00:00:00.000
```

**三种 Offset 提交方式：**

| 方式 | 说明 |
|------|------|
| **自动提交** | `enable.auto.commit=true`，默认 5 秒一次。可能重复消费 |
| **手动同步提交** | `consumer.commitSync()`，等 Broker 确认。安全但慢 |
| **手动异步提交** | `consumer.commitAsync()`，不阻塞但可能提交失败 |

### 4.5 消息不丢配置

| 环节 | 参数 | 说明 |
|------|------|------|
| **Producer** | `acks=all` | 等所有 ISR 副本确认才返回成功 |
| **Producer** | `retries=3` | 失败重试 |
| **Broker** | `replication.factor=3` | 副本数 ≥ 3 |
| **Broker** | `min.insync.replicas=2` | 最少同步副本数 |
| **Consumer** | 手动提交 offset | 处理完业务再提交 |

### 4.6 消息重复 vs 不丢

> Kafka 设计取舍：**At Least Once（至少一次）= 不丢，可能重复。**
>
> **幂等生产者** `enable.idempotence=true`：Producer 发消息时带 ProducerID + SequenceNumber，Broker 去重，保证在单 Partition 内不重复。
>
> **消费端幂等：** 业务唯一 ID + Redis/DB 判重。

### 4.7 分区策略

```
Producer 发消息时决定进哪个 Partition：

1. 指定 key → hash(key) % partition 数
   → 相同 key 进同一分区 → 天然有序

2. 不指定 key → 轮询（2.4 后默认 sticky）
   → 先攒一批发给同一 Partition，减少网络开销
```

---

## 五、消息丢失与可靠性

| 环节 | 丢失场景 | 解决方案 |
|------|------|------|
| **生产者 → Broker** | 网络抖动 | 同步发送 + 重试 / Confirm 机制 |
| **Broker 内部** | 宕机 | 同步刷盘 + 主从同步（同步双写） |
| **Broker → 消费者** | 消费者崩溃 | 手动 ack，处理完再确认 |

---

## 五、消息积压处理

1. **紧急扩容：** 增加 Consumer 实例数 = Queue 数
2. **降级处理：** 新 Consumer 直接记日志不处理，快速消费积压
3. **修复后回溯：** 积压清空后，用消息回溯重放数据
4. **转移积压：** 积压消息灌到新 Topic，多 Consumer 并行消化

---

## 六、无顺序消息场景：异步状态处理

**场景：** 订单成功 → 发货，但退款消息可能先到。

| 方案 | 做法 |
|------|------|
| **幂等** | 同一消息重复处理结果一致 |
| **延迟确认** | 发货前等待 N 秒，看有没有退款事件 |
| **状态锁** | 发货前查订单状态，已退款则跳过 |
| **补偿 SAGA** | 已发货后收到退款 → 召回物流 / 客服介入 |

---

## 七、面试话术

### 选型（20 秒版）

> **RabbitMQ：** Erlang，微秒延迟，复杂路由强，万级吞吐。适合金融、复杂路由场景。
> **RocketMQ：** Java，阿里出品，十万级吞吐，原生事务+顺序+回溯。适合电商、分布式事务。
> **Kafka：** Scala，百万级吞吐，顺序写磁盘+零拷贝+PageCache。适合大数据、日志采集、流计算。

### 消息不丢（15 秒版）

> **生产端：** 同步发送 + 重试 / Confirm。**Broker：** 同步刷盘 + 主从同步 / ISR 副本确认。**消费端：** 手动 ack/提交 offset，处理完再确认。

### Kafka 高吞吐原因（15 秒版）

> 顺序写磁盘（append-only）、零拷贝 `sendfile()`（数据从磁盘直接到网卡不经过用户态）、PageCache 读写缓存、分区并行处理、批量读写。

### RocketMQ 事务消息（20 秒版）

> **两阶段：** 先发半消息（消费者不可见）→ 执行本地事务 → 成功 Commit / 失败 Rollback。长时间未决 → Broker 反查生产者本地事务状态。

### RabbitMQ 死信队列（15 秒版）

> 消息被拒绝且不重回队、TTL 过期、队列满 → 自动转入死信交换机 → 死信队列。用途：延迟消息、故障排查、重试。

### 顺序消息（10 秒版）

> 同一业务 key 发到同一 Queue，Consumer 单线程消费该 Queue。RocketMQ 原生支持，Kafka 分区内有序，RabbitMQ 不保证。
