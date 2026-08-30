# RabbitMQ 消息类型与属性映射

> 纠偏：RabbitMQ 没有 RocketMQ 式"消息类型"，只有 AMQP 消息属性 + 组合技巧

---

## 一、概念纠偏（面试最常见误解）

```
RocketMQ：Broker 端【内建】消息类型
  普通/顺序/事务/延迟/批量 —— 全是 Broker 原生能力，直接 API 用

RabbitMQ：AMQP 协议，没有"消息类型"这回事
  它只有【消息属性 properties】+【组合技巧】
  延迟 = TTL + 死信 或 插件；顺序 = 自己保证；事务 = 基本没有
```

**问"RabbitMQ 有几种消息类型"是伪问题——它和 RocketMQ 是两套体系，RocketMQ 按"能力"分类型，RabbitMQ 按"协议属性"表达。**

---

## 二、RabbitMQ 的消息属性（Properties）

RabbitMQ 消息由 body（正文）+ properties（属性头）组成，属性才是关键：

| 属性 | 作用 | 对应"类消息类型" |
|------|------|-----------------|
| `deliveryMode` | 1=非持久 2=持久 | 持久化消息 |
| `expiration` | 单条消息 TTL（毫秒） | 延迟消息原料 |
| `priority` | 优先级 0~255（需队列开 x-max-priority） | 优先级消息 |
| `headers` | 自定义键值头 | headers 路由 / RPC 的 correlationId |
| `messageId` | 消息标识 | 幂等去重 |
| `correlationId` | 关联 ID | RPC 请求响应匹配 |
| `replyTo` | 回调队列 | RPC 应答投递目标 |
| `timestamp` | 时间戳 | 顺序/时间判断辅助 |

**消息属性 + 交换机路由 + 队列参数（TTL、DLX、priority）= RabbitMQ 的全部"类型"能力。**

---

## 三、与 RocketMQ 消息类型逐一对照（核心）

| RocketMQ | RabbitMQ 能否做到 | 实现方式 |
|----------|------------------|---------|
| 普通消息 | ✅ | 默认即可 |
| 延迟消息 | ✅ | ① TTL+死信队列 ② delayed-message-exchange 插件 |
| 顺序消息 | ⚠️ 不保证 | 单队列 + 单消费者 + `basicQos(1)`；多消费者/重试/多线程即乱序 |
| 事务消息 | ❌ 做不到 | `channel.txSelect` 笨重事务性能极差；publisher confirm 只是"送达确认"，不是"本地事务+消息原子" |
| 批量消息 | ⚠️ 无原生 | 自己攒批发送 |
| 消息过滤 | ✅ | 交换机路由（direct/topic）+ 消费端过滤；**无 Broker 端索引级过滤** |

---

## 四、逐项展开

### 1. 延迟消息（已有文档，最常问）

```
方式一：死信 TTL（原生）
  消息投到带 TTL 的队列 → 过期 → 变死信 → 被投到 DLX 绑定的业务队列
  缺点：只能队列级 TTL，粒度粗

方式二：delayed-message-exchange 插件（推荐）
  Exchange 收到带 x-delay 头部的消息 → 内部延迟到期后再投递
  优点：消息级任意延迟

详见《RabbitMQ延迟队列实现原理.md》
```

### 2. 顺序消息：RabbitMQ 不保证，只能"模拟"

```
单队列 + 单消费者 + prefetch=1：
  队列 FIFO + 一个消费者串行处理 → 勉强有序

会打破顺序的情况：
  ① 多个消费者竞争消费（工作队列）→ 乱序
  ② 失败重试（basicNack requeue=true）→ 该消息重回队尾，顺序被打乱
  ③ 消费者内部多线程处理 → 乱序
  ④ 消息过期进死信再投 → 乱序
```

**结论：需要严格顺序的场景，RabbitMQ 不是好选择，这是 RocketMQ 分区顺序消息的主场。**

### 3. 事务消息：RabbitMQ 没有

```
RabbitMQ 只有：
  channel.txSelect() 事务 —— 性能极差（同步阻塞，吞吐掉一个量级），几乎不用
  publisher confirm   —— 保证"消息送达 Broker"，但解决不了"本地事务和消息的原子性"

RocketMQ 事务消息要解决的问题（先扣库存再发消息，两步原子）：
  RabbitMQ 无原生方案，只能业务自己搞本地消息表 + 定时补偿
```

### 4. 优先级消息

```java
// 队列声明时开优先级
Map<String, Object> args = new HashMap<>();
args.put("x-max-priority", 10);
channel.queueDeclare("priority.queue", true, false, false, args);

// 消息带优先级
AMQP.BasicProperties props =
    new AMQP.BasicProperties.Builder().priority(5).build();
channel.basicPublish("", "priority.queue", props, body);
```

```
原理：优先级队列内部按 priority 排序，高优先级先出队
代价：性能下降（插入要排序），队列越大越明显
```

### 5. 持久化消息

```
deliveryMode=2 + 队列持久化(durable=true) + 交换机持久化 = 消息不丢
注意：持久化只保证"重启不丢"，不保证"发送即落盘"（默认异步刷盘）
```

---

## 五、面试话术（30 秒）

> RabbitMQ 没有 RocketMQ 那种"消息类型"——它是 AMQP 协议，能力靠消息属性 + 组合技巧表达：延迟用 TTL+死信或延迟插件，优先级靠 priority 属性和 x-max-priority 队列，持久化靠 deliveryMode。和 RocketMQ 对照：普通、延迟 RabbitMQ 能做；顺序只保证单队列单消费者 prefetch=1，多消费者或重试就乱；事务消息完全没有，publisher confirm 只是送达确认不是事务原子；批量要自己攒批。一句话：RocketMQ 按能力内建类型，RabbitMQ 按协议属性表达，延迟靠死信、顺序和事务要业务自己扛。
