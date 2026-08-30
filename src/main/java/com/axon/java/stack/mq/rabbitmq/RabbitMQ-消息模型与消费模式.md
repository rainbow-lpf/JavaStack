# RabbitMQ 消息模型与消费模式

> 简单队列 / 工作队列 / 发布订阅 / 路由 / 主题 / RPC 六种消息模式 + 底层机制

---

## 一、六种消息模式全景

| 模式 | 交换机 | 关键点 | 场景 |
|------|--------|--------|------|
| 简单队列 | 默认（direct） | 一生产者一消费者 | 入门 |
| 工作队列 Work Queue | 默认（direct） | 多消费者**竞争消费**，一条消息只被一个消费 | 任务分发、削峰 |
| 发布/订阅 Publish/Subscribe | **fanout** | 广播，每条消息所有队列各一份 | 通知推送 |
| 路由 Routing | **direct** | 按 RoutingKey 精确分发 | 日志分级 |
| 主题 Topics | **topic** | 通配符 * # 模式匹配 | 灵活多维度路由 |
| RPC | — | 请求-应答，靠 correlationId + replyTo | 远程调用 |

---

## 二、工作队列模式（面试重点：竞争消费 + 公平分发）

### 模型

```
Producer → Queue → [Consumer-1, Consumer-2, Consumer-3]
                     ↑ 一条消息只会被其中一个消费（轮询分发）
```

### 底层：默认轮询（round-robin）

```
默认 prefetch 无限制：Broker 把消息平均轮询发给每个消费者
  问题：奇偶消息耗时不同 → 快的消费者闲死，慢的消费者堆积
```

### 公平分发：basicQos(prefetchCount)

```java
channel.basicQos(1);   // 每个消费者最多同时处理 1 条，处理完 ack 才发下一条
```

```
prefetch=1 → 能干就多干，干不完不硬塞 → 公平
prefetch=N → 一次最多 N 条在途，吞吐和公平的平衡
```

**核心机制：prefetch 就是 RabbitMQ 真推模式下的"流量控制阀"——因为 Broker 主动推，必须靠它防打爆消费者。**

---

## 三、发布/订阅 + 路由 + 主题

三种模式的本质区别**只在交换机类型**：

```
发布/订阅 = fanout（广播，忽略 routing key）
路由     = direct（routing key 完全匹配）
主题     = topic （routing key 通配符匹配）

队列本身没有任何区别，区别全在 Exchange 怎么路由
```

详见《RabbitMQ-交换机类型与路由原理.md》。

---

## 四、RPC 模式（RabbitMQ 也能当 RPC 用）

### 模型

```
Client ──发请求──▶ rpc_queue（带上 correlationId + reply_to 回调队列）
Server 消费 rpc_queue → 处理 → 把结果发到 reply_to 队列
Client 监听 reply_to → 按 correlationId 匹配是哪次请求的响应
```

### 两个关键字段

```
correlationId：请求唯一 ID——响应回来时用它匹配"这是哪次请求"
replyTo      ：回调队列名——告诉 Server 结果发回哪个队列
```

### 为什么不用临时队列每次新建

```
每次请求建一个回调队列成本高；复用单回调队列 + correlationId 匹配
```

---

## 五、底层确认机制（和消费模式强相关）

### 生产者确认（Publisher Confirm）

```
confirm 模式：Broker 收到消息后给 Producer 回 ACK
  - 单条确认：发一条等一条（慢）
  - 批量确认：攒一批一起确认（快，但失败要整批重发）
  - 异步确认：回调 + 序号，高性能标准做法
```

### 消费者确认（Consumer Ack）

```
自动 ack：Broker 发出就算消费成功 → 可能丢（消费者崩了）
手动 ack：业务处理完才 basicAck → 可靠
  - 失败可 basicNack（requeue=true 重回队列 / false 丢弃进死信）
```

---

## 六、面试话术（30 秒）

> RabbitMQ 六种消息模式：简单队列、工作队列、发布订阅、路由、主题、RPC，后三种的区别只在交换机——fanout 广播、direct 精确、topic 通配符。工作队列是竞争消费，一条消息只被一个消费者消费；因为 RabbitMQ 是 Broker 真推，要用 basicQos(prefetch) 做流控，否则慢消费者堆积快消费者闲死。RPC 模式靠 correlationId 匹配请求响应、replyTo 指定回调队列。可靠性两头：生产者 confirm 确认送达、消费者手动 ack 确认处理完，中间消息持久化兜底。
