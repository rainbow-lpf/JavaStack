# RocketMQ 顺序消息原理

> 从"为什么乱序"到"三环节怎么锁顺序"，把顺序消息的代价讲透

---

## 〇、快递场景先理解

```
顺序消息 = 拆成好几个箱子寄，必须按 1 号箱 → 2 号箱 → 3 号箱的顺序送到，不能乱。

怎么做？
  ① 寄件时：同一个订单的箱子全部交到【同一个快递站】——
            不能这个箱给 A 站、下个箱给 B 站，那到货顺序就乱了
  ② 运输时：同一个站的箱子按到达顺序排号（快递站按号存放，天然有序）
  ③ 取件时：朋友（Consumer）按号一个个拆箱，拆完 1 号才拆 2 号

对应 RocketMQ 三环节：
  同一个 key（订单号）→ 永远同一个 MessageQueue
  单 Queue 内消息 offset 递增 → 天然 FIFO
  MessageListenerOrderly 单线程逐条消费
```

---

## 一、问题场景

```
订单生命周期：创建 → 支付 → 发货 → 完成
如果顺序反了：先收到"发货"，再收到"支付" → 业务直接崩
```

顺序消息要保证：**同一个业务实体（如一个订单）的消息，按发送顺序被消费。**

---

## 二、为什么普通消息会乱序

回顾普通消息的两处"乱序源头"：

```
① 生产端：Producer 轮询选 Queue → 同一订单的两条消息落进不同 Queue
          → Queue 之间无顺序关系 → 消费时谁先谁后不定

② 消费端：并发模式（MessageListenerConcurrently）多线程拉取 + 失败转投 %RETRY%
          → 重试消息从别的 Topic 回来，顺序彻底打乱
```

所以顺序消息要做的，就是把这两处都"锁死"。

---

## 三、三环节锁顺序（核心）

### 环节 1：生产端——同一 key 进同一 Queue

```java
producer.send(
    new Message("order-topic", "order123", body),
    (mqs, msg, arg) -> mqs.get(Math.abs(arg.hashCode()) % mqs.size()),
    "order123"          // 业务 key：订单号
);
// MessageQueueSelector：同一 order123 永远 hash 到同一个 Queue
```

**路由公式：`Math.abs(key.hashCode()) % 队列数`** —— 注意两点：

```
① 必须 Math.abs：String.hashCode() 可能是负数，不取绝对值 % 出来负数下标 → 越界
② 极端 bug：Integer.MIN_VALUE 的 Math.abs 仍是负数，更稳的写法：
   int index = (key.hashCode() & Integer.MAX_VALUE) % mqs.size();  // 清符号位
```

不写 selector 的话，默认是【轮询】——同一 key 会散到不同 Queue，顺序就没了。

```
order123 → 永远 Queue-2
order456 → 永远 Queue-0
→ 全局可以乱，但【单队列内有序】
```

### 环节 2：存储端——单 Queue 天然 FIFO

```
CommitLog 顺序写 → 同 Queue 的消息 offset 严格递增
ConsumeQueue 按 offset 组织 → 拉取时天然按发送顺序返回
→ 存储层不需要任何额外动作，append-only 自带顺序
```

### 环节 3：消费端——单线程 + 队列锁

```
并发模式：一个 Queue 可以被组内多线程同时拉 → 乱
顺序模式：MessageListenerOrderly
  ├─ Broker 给队列加分布式锁（同一队列同一时刻只分配一个消费者）
  ├─ 消费端对 ProcessQueue 加本地锁
  └─ 单线程逐条消费：处理完 #1 才拉 #2
```

```
Queue-2: [创建] → [支付] → [发货]
           ↑
    单线程，一条一条过，前一条 SUCCESS 才取下一条
```

---

## 四、顺序消息的代价（面试必问）

| 代价 | 说明 |
|------|------|
| **吞吐低** | 一个 key 一个队列一个线程，热点 key 直接成瓶颈 |
| **一条卡住，全队列堵** | 顺序模式消费失败 → `SUSPEND_CURRENT_QUEUE_A_MOMENT` 原地重试同一条，后面全排队等 |
| **不能批量并发** | 放弃了并发消费换取顺序 |
| **重试受限** | 不能转投 %RETRY%（会乱序），只能原地重试 |

```
顺序模式的失败重试：
  失败 → 原地等 → 再试同一条 → 直到成功或达到 maxReconsumeTimes
  ★ 跳过去就乱序了，所以只能死磕
  死磕太久 → 人工介入 + 跳过策略（业务要容忍"放弃某条"）
```

### 4.1 一条失败会不会阻塞同队列后续消息？

分两端看：

```
生产端（发送失败）→ 不阻塞
  order123 send 失败是独立动作，order789 照常发进 q1
  各消息发送互不依赖

消费端（消费失败）→ 会阻塞 ★ 经典坑
  q1: [order123] → [order789]
  order123 消费失败
    → 不跳过、不转投（转投会乱序）
    → 本地延迟 SUSPEND_CURRENT_QUEUE_A_MOMENT（默认 1s）
    → reconsumeTimes +1，原地重试 order123
    → order789 排队等，一直阻塞
  order123 一直失败 → order789 一直卡死
```

### 4.2 maxReconsumeTimes 与死信队列 DLQ

**maxReconsumeTimes**：消息消费失败后最大重试次数，默认 **16**。

三个配置入口：
```java
consumer.setMaxReconsumeTimes(20);                    // ① 消费端代码
// mqadmin updateSubGroup -g group -n localhost:9876 -r 20  ② 命令行
// Dashboard 订阅组 → 消费组详情 → 最大重试次数            ③ 控制台
```

达到上限后的完整流程：
```
order123 第 16 次（默认值）仍失败
  → reconsumeTimes >= maxReconsumeTimes
  → 触发 sendMessageBack(msg)：把消息重新发到 %RETRY%order-consumer-group
     delayLevel = 3 + reconsumeTimes（越重试延迟越久）
  → broker 判断超限 → 投递死信队列 %DLQ%order-consumer-group
  → order123 从原队列移除
order789 此时才被继续消费 ✅
```

**死信队列（DLQ）是 RocketMQ 自带的，不用自己定义：**
```
① broker 自动创建，命名固定 %DLQ% + 消费组名（如 %DLQ%order-consumer-group）
② 默认只写不读（perm=WRITE）：消息进得去，消费者拉不出来，防误消费
③ 重试 + 超限转 DLQ 全链路 SDK 自动完成
   业务侧只做一件事：消费失败正确返回状态，别吞异常
     顺序模式：return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
     并发模式：return ConsumeConcurrentlyStatus.RECONSUME_LATER;
```

**死信队列本质 = 普通 topic**：
```
存储完全一样：走同一 CommitLog + ConsumeQueue，消息结构不变
特殊在【语义】：命名固定 + 默认只写不读 + 自动创建 + 不自动消费
类比：一个"垃圾房"，结构和普通仓库一样，但只进不出，等管理员清
```

**怎么查死信：**
```
① Dashboard 消息页面 → Topic 选 %DLQ%消费组名 → 按时间/msgId/keys 查
② mqadmin printMsg -t %DLQ%order-consumer-group -c false
"只写不读"只对消费者生效，控制台查询接口照样能翻出来
```

**业务侧还要做三件事：**
```
① DLQ 消费：默认拉不到，要处理时改 DLQ 权限加 READ 或单独订阅捞取
② DLQ 告警：消息进死信说明业务有问题，加监控，别让 DLQ 悄悄堆满
③ 顺序场景人工跳过：靠 DLQ 放行要等重试满 16 次，热点场景可自行判断 reconsumeTimes 提前跳过
```

**maxReconsumeTimes 在顺序消息里的特殊意义：**
```
| 模式 | 重试方式 | 超过上限后 |
| 并发 | 转投 %RETRY% 延迟重试，不阻塞队列 | 进 %DLQ%组 |
| 顺序 | 原地重试同一条，阻塞整个队列 | 进 %DLQ%组 |
设小点 → 脏数据快点进 DLQ 放行队列；设太大 → 队列卡死时间长
```

---

## 五、全局顺序 vs 分区顺序

| | 全局顺序 | 分区顺序（常用） |
|---|---------|----------------|
| 范围 | 整个 Topic 只有 1 个 Queue | 按 key 分到多个 Queue，key 内有序 |
| 实现 | 所有消息进同一 Queue | MessageQueueSelector 按 key 选队列 |
| 吞吐 | 极低（单队列单线程） | 较好（多队列并行） |
| 场景 | 几乎不用 | **电商订单、支付流水（推荐）** |

**生产实践 99% 用分区顺序：只要"同一订单的消息有序"，不需要"所有订单全局有序"。**

---

## 六、两个易踩的坑

```
坑 1：生产端同步发送 + 失败重试可能换 Queue 重发 → 乱序
      → 顺序消息要关闭/谨慎使用重试，或用退避重试且保证同队列

坑 2：一个 key 消息量极大（如大促爆款商品）→ 单队列成为热点
      → 吞吐上不去，需要业务层拆 key 或接受部分乱序
```

---

## 七、面试话术（30 秒）

> 顺序消息三环节锁顺序：生产端用 MessageQueueSelector 按业务 key 选队列，同一订单永远进同一个 Queue；存储端单 Queue 天然 FIFO——CommitLog 顺序写、offset 递增；消费端用 MessageListenerOrderly，Broker 给队列加锁 + 消费端单线程逐条消费。代价是吞吐低、一条失败全队列阻塞——失败只能原地重试不能转投，否则乱序。实践上分区顺序就够用，同一订单有序即可，全局顺序几乎不用。

---

## 八、完整代码案例

### 8.1 Producer（按 key 选队列）

```java
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.List;

public class OrderProducer {
    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("order-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();

        // 订单 123 的创建、支付、发货 —— 必须进同一个 Queue
        String orderId = "order123";

        sendOrderMsg(producer, orderId, "创建订单");
        sendOrderMsg(producer, orderId, "支付订单");
        sendOrderMsg(producer, orderId, "发货");

        producer.shutdown();
    }

    private static void sendOrderMsg(DefaultMQProducer producer, String orderId, String action) throws Exception {
        Message msg = new Message("order-topic", "order", (action).getBytes());

        // 关键：MessageQueueSelector 按 orderId hash 选队列
        SendResult result = producer.send(msg, new MessageQueueSelector() {
            @Override
            public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                String key = (String) arg;
                int index = Math.abs(key.hashCode()) % mqs.size();  // 防负数下标
                return mqs.get(index);
            }
        }, orderId);

        System.out.printf("%s → queueId=%d%n", action, result.getMessageQueue().getQueueId());
    }
}
```

### 8.2 Consumer（MessageListenerOrderly）

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.List;

public class OrderConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("order-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("order-topic", "*");

        // 关键：顺序监听器（不是 MessageListenerConcurrently）
        consumer.registerMessageListener(new MessageListenerOrderly() {
            @Override
            public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs, ConsumeOrderlyContext context) {
                for (MessageExt msg : msgs) {
                    System.out.printf("queueId=%d, offset=%d, 内容=%s%n",
                            msg.getQueueId(), msg.getQueueOffset(), new String(msg.getBody()));
                    // 单线程逐条处理：创建 → 支付 → 发货
                }
                // 成功返回 SUCCESS；失败返回 SUSPEND_CURRENT_QUEUE_A_MOMENT（原地重试）
                return ConsumeOrderlyStatus.SUCCESS;
            }
        });

        consumer.start();
        System.out.println("顺序消费者已启动");
    }
}
```

### 8.3 关键差异速记

| 环节 | 普通消息 | 顺序消息 |
|------|---------|---------|
| 发消息 | `producer.send(msg)` 轮询 | `producer.send(msg, selector, key)` 按 key 选队列 |
| 监听器 | `MessageListenerConcurrently` | `MessageListenerOrderly` |
| 失败返回 | `RECONSUME_LATER` | `SUSPEND_CURRENT_QUEUE_A_MOMENT` |
| 消费线程 | 多线程并发 | 单线程 + 队列锁 |
