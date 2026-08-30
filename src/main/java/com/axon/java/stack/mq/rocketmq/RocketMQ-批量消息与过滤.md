# RocketMQ 批量消息与消息过滤

> 批量发送的边界 + Tag/SQL 两种过滤原理

---

## 〇、快递场景先理解

```
批量消息 = 快递凑一车再发
  你每次寄一个包裹就跑一趟快递站太亏，不如攒一批一起拉过去——
  一次跑腿（一次网络往返）送 N 个包裹，摊薄成本。
  但一车有上限：装不下 4MB 的货，超了得拆车或压缩。

消息过滤 = 快递站代收时先挑拣
  朋友只订阅"标了【订单】标签的包裹"，快递站（Broker）派送前就按标签挑好，
  不相关的包裹根本不用往朋友家拉，省了运输（网络带宽）。
  标签（Tag）= 贴在包裹上的分类贴纸，派送前先在快递站核对。
```

---

## 一、批量消息：把多条消息一车拉走

### 为什么需要

```
每条消息都单独一次网络往返 + 一次刷盘确认 → 高并发场景吞吐上不去
批量 = 一次发送多条，摊薄网络和确认开销
```

### 用法

```java
List<Message> msgs = new ArrayList<>();
msgs.add(new Message("topicA", "tagA", body1));
msgs.add(new Message("topicA", "tagA", body2));
producer.send(msgs);   // 一次发送
```

### 三条硬性边界（面试常考）

```
① 同一批必须同一个 Topic，等待的是同一个存储过程
② 单条消息不能超过 4MB（消息过大直接报错）
③ 总大小不能超过 4MB（超过要分批，或压缩：msg.body 自己 gzip）
```

> **为什么有 4MB 上限**：CommitLog 里一条消息要能塞进一个文件（1GB 文件没问题，限制主要是网络传输 + 反序列化 + 堆积时内存占用考量）。

### 和 Kafka 批量对比（进阶加分）

```
Kafka：Producer 攒批（linger.ms/batch.size），批量是常态，吞吐核心
RocketMQ：批量是可选优化，不是核心机制
         （因为 RocketMQ 本身就是顺序写，单条也快）
```

---

## 二、消息过滤：Broker 端拦截，不是消费端过滤

### 过滤位置

```
消费者只订阅自己关心的消息，过滤发生在 Broker 端（推送前）
→ 省网络带宽，不用把不要的消息都拉回来
```

### 方式 1：Tag 过滤（默认，最常用）

```
消息带 tag：new Message("topic", "tagA", body)
消费者订阅：subscribe("topic", "tagA || tagB")   // tagA 或 tagB
```

**实现原理：ConsumeQueue 索引里的 tagHash**

```
ConsumeQueue 条目 20 字节 = offsetPy(8) + sizePy(4) + tagHash(8)
Broker 收到拉取请求 → 比对索引里的 tagHash 和订阅 tag 的 hash
  → 不匹配直接跳过，连 CommitLog 正文都不去读 → 省 IO
```

**注意：tagHash 只有 8 字节，hash 冲突极小概率存在，所以 Tag 过滤不是 100% 精确，冲突时多返回一条，消费端要自己再过滤一次。**

### 方式 2：SQL 过滤（SQL92 表达式）

```java
// 消息带自定义属性
msg.putUserProperty("age", "18");
msg.putUserProperty("city", "shanghai");

// 消费者按 SQL 表达式订阅
subscribe("topic", MessageSelector.bySql("age > 16 and city = 'shanghai'"));
```

**实现原理：Broker 端过滤（需要 `enablePropertyFilter=true`）**

```
Broker 收到消息 → 按属性过滤表达式做计算
  匹配 → 投递给消费者
  不匹配 → 不投递
代价：每条消息要在 Broker 端做表达式计算，CPU 开销比 tagHash 大
适用：属性维度多、组合复杂，tag 表达不了时用
```

### Tag vs SQL 过滤对比

| | Tag 过滤 | SQL 过滤 |
|---|---------|---------|
| 原理 | 索引 tagHash 比对，几乎零开销 | Broker 端属性表达式计算 |
| 精确度 | 极小概率 hash 冲突 | 精确 |
| 表达能力 | 只能 `\|\|`（或） | 完整 SQL92（and/or/比较/括号） |
| 性能 | 高 | 低（每条都要算） |
| 开启 | 默认 | 需 `enablePropertyFilter=true` |

---

## 三、面试话术（30 秒）

> 批量消息一次发多条摊薄开销，但同批必须同 Topic、总大小不超 4MB、超了要分批或自行压缩。消息过滤发生在 Broker 端不是消费端：Tag 过滤靠 ConsumeQueue 索引里的 8 字节 tagHash 比对，不匹配连正文都不读，几乎零开销——但 hash 有极小概率冲突，消费端要兜底再过滤；SQL 过滤支持 SQL92 表达式，Broker 端逐条计算属性，精确但开销大。一句话：Tag 是索引级快过滤，SQL 是属性级精确过滤。

---

## 四、完整代码案例

### 4.1 批量发送

```java
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.util.ArrayList;
import java.util.List;

public class BatchProducer {
    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("batch-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(new Message("batch-topic", "tagA",
                    ("批量消息-" + i).getBytes()));
        }

        // 一次发送 10 条，摊薄网络开销
        SendResult result = producer.send(msgs);
        System.out.println("批量发送成功: " + result.getMsgId());

        producer.shutdown();
    }
}
```

**批量三条铁律**：同批同 Topic；单条 < 4MB；总大小 < 4MB（超了分批或压缩）。

### 4.2 Tag 过滤

```java
// Producer：消息带 tag
Message msg = new Message("filter-topic", "order", "订单消息".getBytes());
Message msg2 = new Message("filter-topic", "pay", "支付消息".getBytes());
producer.send(msg);
producer.send(msg2);
```

```java
// Consumer：只订阅 order 标签
consumer.subscribe("filter-topic", "order");          // 只收 order
// consumer.subscribe("filter-topic", "order || pay"); // 收 order 或 pay
```

### 4.3 SQL 过滤（需 Broker 开启 enablePropertyFilter=true）

```java
// Producer：消息带自定义属性
Message msg = new Message("filter-topic", "user", "用户消息".getBytes());
msg.putUserProperty("age", "18");
msg.putUserProperty("city", "shanghai");
producer.send(msg);
```

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.MessageSelector;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class SqlFilterConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("filter-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");

        // SQL92 表达式过滤：age > 16 且 city = shanghai
        consumer.subscribe("filter-topic",
                MessageSelector.bySql("age > 16 and city = 'shanghai'"));

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                System.out.println("命中过滤条件: " + new String(msg.getBody()));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
    }
}
```
