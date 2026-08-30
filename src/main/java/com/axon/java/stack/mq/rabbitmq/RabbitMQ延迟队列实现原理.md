> **总结日期：** 2026-07-07
> **核心主题：** RabbitMQ 延迟队列两种实现方式

---

## 一、死信 TTL 方式（原生，队列级延迟）

### 原理

消息进入一个**无消费者**的队列等待 TTL 超时，到期后 RabbitMQ 自动将消息标记为**死信**，根据队列声明的转发规则投递到真正的消费队列。

### 核心组件

| 组件 | 说明 |
|---|---|
| 死信 | 消息的一种**状态标记**，不是队列类型 |
| 延迟队列 | 普通队列，设了 TTL，**没有消费者** |
| 消费队列 | 普通队列，**有消费者**，接收死信转发 |

### 死信产生的三种情况

1. TTL 超时 —— 消息存活时间超过 `ttl`
2. 队列满 —— 队列长度超限，溢出消息变死信
3. 消费者拒绝 —— `basicNack` / `basicReject` 且 `requeue=false`

### 完整流程图

```
Producer
  │
  │  routingKey = "delay"
  ▼
delay.exchange
  │
  │  匹配 "delay"
  ▼
delay.queue (TTL = 30s，无消费者)
  │
  │  30 秒到期 → 标记为死信
  │  读取队列配置:
  │    deadLetterExchange = "delay.exchange"
  │    deadLetterRoutingKey = "real"
  │
  │  自动重新投递，routingKey 改为 "real"
  ▼
delay.exchange
  │
  │  匹配 "real"
  ▼
real.queue → Consumer
```

### 关键配置

```java
// 延迟队列：TTL + 死信转发规则
@Bean
Queue delayQueue() {
    return QueueBuilder.durable("delay.queue")
        .ttl(30000)                                   // (1) 存活 30 秒
        .deadLetterExchange("delay.exchange")          // (2) 死后发给谁
        .deadLetterRoutingKey("real")                  // (3) 转发用的 routing key
        .build();
}

// 绑定：routing key = "delay" → delay.queue
@Bean
Binding delayBinding() {
    return BindingBuilder.bind(delayQueue()).to(delayExchange()).with("delay");
}

// 绑定：routing key = "real" → real.queue
@Bean
Binding realBinding() {
    return BindingBuilder.bind(realQueue()).to(delayExchange()).with("real");
}
```

### 三个参数说明

| 参数 | 含义 | 通俗说法 |
|---|---|---|
| `ttl(30000)` | 消息最多存活 30 秒 | "30 秒后判死刑" |
| `deadLetterExchange` | 死后投给哪个交换机 | "尸体送哪" |
| `deadLetterRoutingKey` | 转发时的 routing key | "进门暗号" |

### 痛点

- TTL 是**队列级别**的，同一队列中所有消息延迟时间相同
- 需要多种延迟时间时，必须创建多个延迟队列（3s / 10s / 30s / 10min…）
- 每新增一种延迟 = 新增一条队列

---

## 二、延迟插件方式（rabbitmq-delayed-message-exchange）

### 原理

安装插件后，交换机类型为 `x-delayed-message`。消息到达交换机**不立即投递**，而是存入内部 Mnesia 表，由交换机定时器倒计时，到期后才投递给目标队列。**没有死信，不需要第二条队列。**

### 安装

```bash
rabbitmq-plugins enable rabbitmq_delayed_message_exchange
```

### 流程图

```
Producer
  │
  │  header: x-delay = 30000
  ▼
┌─────────────────────────────────────────────┐
│  delay.exchange (type = x-delayed-message)   │
│                                             │
│  收到消息 → 不投递                            │
│  从 header 读取 x-delay = 30000             │
│  存入 Mnesia 表 (内存 + 磁盘)                 │
│                                             │
│  ┌──────────────────────────┐               │
│  │ Mnesia 表                │               │
│  │ msg1 → delay=10000 ✓到期  │  取出投递      │
│  │ msg2 → delay=30000 倒计时 │               │
│  │ msg3 → delay=60000 倒计时 │               │
│  └──────────────────────────┘               │
│                                             │
│  定时器轮询: 当前时间 >= 入队时间 + delay      │
│  到期 → 投递给绑定队列                        │
└───────────────────────┬─────────────────────┘
                        │
                        ▼
                  real.queue → Consumer
```

### 关键理解

- 消息**从未进入队列**等待，是**交换机在"扣留"消息**
- 不存在"死信"状态，消息是正常过期投递
- 一条队列即可，无数种延迟时间

### 代码示例

```java
// 延迟交换机
@Bean
CustomExchange delayedExchange() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-delayed-type", "direct");
    return new CustomExchange("delay.exchange", "x-delayed-message", true, false, args);
}

// 发送时指定延迟
rabbitTemplate.convertAndSend("delay.exchange", "rk", msg, message -> {
    message.getMessageProperties().setDelay(10000);  // 10 秒
    return message;
});

rabbitTemplate.convertAndSend("delay.exchange", "rk", msg, message -> {
    message.getMessageProperties().setDelay(60000);  // 60 秒
    return message;
});
```

### 注意事项

- 延迟消息存储在 **Mnesia 内存表**，节点重启会丢失（有磁盘备份但倒计时暂停）
- 重启后消息延迟时间会**延长**（倒计时暂停期间不计入）
- 大量消息时 Mnesia 会成为瓶颈

---

## 三、两种方式对比

| | 死信 TTL | 延迟插件 |
|---|---|---|
| **安装插件** | 不需要 | 需要 |
| **延迟粒度** | 队列级，一个队列一个 TTL | 消息级，每条独立延迟 |
| **队列数量** | 至少 2 条（delay + real） | 1 条 |
| **死信** | 有，消息过期标记为死信再转发 | **无** |
| **延迟存储** | 队列（磁盘持久化） | Mnesia 内存表（可落盘） |
| **不同延迟** | N 种延迟 = N 个队列 | 一个交换机全搞定 |
| **可靠性** | 高，消息持久化到队列 | 节点重启丢消息/延长延迟 |

---

## 四、常见面试题

**Q1: 死信是什么？**

> 死信是消息的状态标记，不是队列类型。消息变成死信有三种情况：TTL 超时、队列满、消费者拒绝且不重入队。没有所谓"死信队列"这个概念，只有"死信消息 + 死信交换机"。

**Q2: Producer 到底发到哪个队列？Consumer 消费哪个队列？**

> 死信 TTL 方式：
> Producer → delay.queue（无消费者等过期）→ 死信转发 → real.queue → Consumer
>
> 延迟插件方式：
> Producer → delay.exchange（扣留倒计时）→ real.queue → Consumer（同一条）

**Q3: 一个交换机挂两个队列，怎么决定消息走哪条？**

> 通过 routing key。`"delay"` → delay.queue，`"real"` → real.queue。
> delay.queue 上配的 `deadLetterRoutingKey("real")` 会在消息死后把 routing key 从 `"delay"` 改为 `"real"`，自然就走到了 real.queue。

**Q4: 延迟插件不需要死信，消息存在哪？**

> 存在交换机内部的 Mnesia 表（Erlang 自带分布式数据库），同时有磁盘持久化。交换机定时器轮询，当前时间 >= 入队时间 + delay 时触发投递。
