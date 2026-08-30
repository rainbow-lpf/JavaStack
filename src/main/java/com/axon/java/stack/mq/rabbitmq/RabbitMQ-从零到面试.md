# RabbitMQ 从零到面试 — 完整指南

> 适用：第一次接触 MQ 的初学者

---

## 第一章：什么是消息队列？为什么需要它？

### 1.1 现实生活比喻

```
你点了外卖。
   ↓
老板接到订单 ← 开始做菜
   ↓
外卖小哥取餐 ← 送到你手上

这里的"外卖平台"就是消息队列。
你（生产者）不用等菜做好 → 下单了就忙自己的事
老板（消费者）按自己的节奏做菜
外卖小哥：中间传递的角色
```

### 1.2 没有消息队列的世界

```java
// 下单后要等发短信、发邮件、记录日志全部完成才返回给用户
public Order createOrder() {
    saveToDB(order);          // ① 存库
    sendSms(order);           // ② 发短信 —— 这一步可能要 1 秒
    sendEmail(order);         // ③ 发邮件 —— 这一步可能失败
    writeLog(order);          // ④ 记日志
    return order;             // 用户等了好久才看到"下单成功"
}
```

### 1.3 有了消息队列

```java
public Order createOrder() {
    saveToDB(order);
    mq.send("order-created", order);  // 发条消息，立刻返回
    return order;                      // 用户秒看到"下单成功"
}

// 短信服务盯着 MQ，收到消息就发短信
// 邮件服务盯着 MQ，收到消息就发邮件
// 日志服务盯着 MQ，收到消息就记日志
```

> **MQ = 中间人。你把事情交代给它，它帮你转发给处理的人，不用你等。**

### 1.4 三个核心角色

```
生产者（送消息的人）  →  MQ（消息队列）  →  消费者（处理消息的人）
    发出                                          接收
    "订单创建了"                               "我来发短信"
```

---

## 第二章：RabbitMQ 的四个核心概念

### 2.1 用邮局来理解

```
你寄信 → 邮局分拣中心 → 按地址投递 → 收信人的信箱 → 收信人取信

RabbitMQ 也一样：
Producer → Exchange（分拣中心）→ Queue（信箱）→ Consumer（收信人）
          根据 RoutingKey（地址）决定丢到哪个 Queue
```

### 2.2 四个角色

| 概念 | 比喻 | 作用 |
|------|------|------|
| **Producer** | 寄信人 | 发送消息 |
| **Exchange** | 邮局分拣中心 | 收到消息，按路由规则分发 |
| **Queue** | 收信人的信箱 | 存放消息 |
| **Consumer** | 收信人 | 从信箱取消息并处理 |

### 2.3 Binding（绑定）是什么？

```
Exchange 和 Queue 之间有一根线连着，这根线就是 Binding。
Binding 上写了 RoutingKey。

举例：
Exchange "order-exchange"
  ├─ Binding: routingKey = "order.create"  → Queue "order-queue"
  └─ Binding: routingKey = "order.cancel"  → Queue "cancel-queue"
```

### 2.4 RoutingKey 怎么用？

```java
// Producer 发消息时指定 routingKey
channel.basicPublish("order-exchange", "order.create", null, message);
//                  ↑交换机                ↑routingKey
// 消息到达 Exchange → 匹配到 routingKey="order.create" 的 Binding → 进入 order-queue
```

---

## 第三章：RabbitMQ 的消息确认 — 怎么保证消息不丢？

### 3.1 消息可能丢在哪？

```
Producer ──发送──→ RabbitMQ ──发送──→ Consumer
   ↑                  ↑                  ↑
 可能这里丢         可能这里丢         可能这里丢
```

### 3.2 第一道保险：生产者确认

**问题：** 你发了消息，RabbitMQ 到底收到没有？

```java
// ✅ 开启 Confirm 模式
channel.confirmSelect();
channel.basicPublish(exchange, routingKey, null, message.getBytes());

// 等 RabbitMQ 回确认
if (channel.waitForConfirms()) {
    System.out.println("消息成功到达 RabbitMQ");
} else {
    System.out.println("消息丢了，重发！");
}
```

| 确认结果 | 含义 | 怎么做 |
|------|------|------|
| **ack** | RabbitMQ 收到了 | 没事了 |
| **nack** | RabbitMQ 没收到 | 重发 |
| **Return** | Exchange 收到了，但没匹配到 Queue | 重发或记日志 |

### 3.3 第二道保险：消息持久化

**问题：** RabbitMQ 服务器重启了，消息还在不在？

```java
// ① 队列持久化
channel.queueDeclare("myQueue", true, false, false, null);
//                               ↑ durable 重启后队列还在

// ② 消息持久化
AMQP.BasicProperties props = MessageProperties.PERSISTENT_TEXT_PLAIN;
channel.basicPublish("", "myQueue", props, message.getBytes());
//                                    ↑ 消息也存盘 — 重启后消息还在
```

### 3.4 第三道保险：消费者确认

**问题：** RabbitMQ 把消息发给你了，你到底处理完没有？

```java
// ✅ 手动确认 — 处理完才删消息
channel.basicConsume("myQueue", false, new DefaultConsumer(channel) {
    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, ...) {
        try {
            // 处理业务...
            channel.basicAck(envelope.getDeliveryTag(), false);  // 成功 → 确认
        } catch (Exception e) {
            channel.basicNack(envelope.getDeliveryTag(), false, true); // 失败 → 重回队列
        }
    }
});
```

| 确认方式 | 说明 | 风险 |
|------|------|------|
| **basicAck** | 处理成功，删消息 | |
| **basicNack (requeue=true)** | 处理失败，放回队列让别的消费者试 | |
| **basicNack (requeue=false)** | 处理失败，不进队列了 → 进死信队列 | |
| **basicReject** | 和 nack 一样，但只能单条拒绝 | |

---

## 第四章：死信队列 — 怎么处理"处理不了"的消息？

### 4.1 什么消息会变成死信？

```
正常消息 → 消费者处理 → basicAck → 消息删除

死信（三种情况）：
① 消费者主动拒绝，且不重回队列（nack + requeue=false）
② 消息在队列中太久，过期了（TTL 超时）
③ 队列满了，新消息进不来
```

### 4.2 配备流程

```java
// ① 声明死信交换机
channel.exchangeDeclare("dlx-exchange", "direct");
// ② 声明死信队列
channel.queueDeclare("dlx-queue", true, false, false, null);
// ③ 绑定
channel.queueBind("dlx-queue", "dlx-exchange", "dlx-key");

// ④ 给普通队列配置死信交换机
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "dlx-exchange");  // 死信交换机
args.put("x-dead-letter-routing-key", "dlx-key");    // 死信 key
args.put("x-message-ttl", 60000);                     // 60 秒过期
channel.queueDeclare("normal-queue", true, false, false, args);
```

### 4.3 死信队列干什么用？

| 场景 | 怎么用 |
|------|------|
| **延迟消息** | 消息设 TTL 30 分钟 → 过期进死信 → 消费者从死信消费 = 30 分钟后才处理 |
| **失败重试** | 消费失败 → 进死信 → 定时从死信重试 |
| **故障排查** | 消费失败的消息都在死信，排查方便 |

---

## 第五章：面试速记

### 5.1 一句话说清 RabbitMQ

> **RabbitMQ 是 AMQP 协议的消息队列，Erlang 写的。核心是 Exchange 交换机 + Queue 队列模型，交换机按 RoutingKey 把消息路由到队列，消费者从队列拿消息。**

### 5.2 消息怎么保证不丢？

> **三道保险：**
> ① **生产者 Confirm** — 发完等 RabbitMQ 回 ack
> ② **持久化** — 队列和消息都存磁盘，重启还在
> ③ **消费者手动 ack** — 处理完再确认，没处理不删消息

### 5.3 死信队列是什么？

> 消息被拒绝且不重回队、TTL 过期、队列满 → 进入死信交换机 → 死信队列。用途：延迟消息、重试、故障排查。

### 5.4 RabbitMQ 的特点

| 优点 | 缺点 |
|------|------|
| 微秒级延迟 | 吞吐量不如 RocketMQ/Kafka |
| 复杂路由能力强 | 不原生支持顺序消息 |
| 多协议支持（AMQP/MQTT/STOMP） | 集群镜像队列有性能开销 |
| 成熟稳定，社区大 | Erlang 源码难维护 |

---

## 第六章：关键词中英文对照

| 中文 | 英文 |
|------|------|
| 交换机 | Exchange |
| 队列 | Queue |
| 绑定 | Binding |
| 路由键 | RoutingKey |
| 生产者 | Producer |
| 消费者 | Consumer |
| 确认 | Acknowledge (ack) |
| 死信 | Dead Letter |
| 持久化 | Durability |
| 过期时间 | TTL (Time-To-Live) |
