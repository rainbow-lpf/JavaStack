# RocketMQ 推拉模式：长轮询的伪装推

> 底层只有拉，Push 是长轮询伪装的——两种模式原理与场景

---

## 〇、快递场景先理解

```
Push（伪推）= 快递员主动送货上门，但其实是"你雇的人"一直帮你盯着货
  你开了个"到货即送"服务（PushConsumer）：
    快递员（框架线程）时刻帮你问快递站"有没有新包裹？"
      ├─ 快递站有货 → 立刻送你家（秒级送达，像推）
      └─ 快递站没货 → 快递员不来回跑，就在站里【等着】，
         货一到马上送（长轮询挂起，不空跑）
  本质：不是快递站主动送，是你雇的人一直在问（拉），只是问得勤、等得住。

Pull（纯拉）= 自己有空才去取件
  你想要"攒够 10 个包裹再一起拉"、或"现在忙，晚点再取"——
  自己决定什么时候去快递站（DefaultLitePullConsumer 手动拉）。
  快递站永远不主动给你送（Broker 从不主动推）。
```

---

## 一、一句话结论

```
RocketMQ 底层：消费者永远是【主动拉 pull】，Broker 从不主动推
Push 模式 = 框架把"拉"封装成"看起来像推"，本质是【长轮询拉取】
```

---

## 二、三家对比

| | RabbitMQ | Kafka | RocketMQ |
|---|----------|-------|----------|
| 模式 | **真推**（Broker push，prefetch 流控） | **纯拉**（Consumer 拉） | **拉 + 长轮询伪装推** |

---

## 三、为什么这么设计

```
纯推的缺点（RabbitMQ 的痛）：
  Broker 不管消费者处理能力，猛推 → 消费者打满 → 堆积在消费者内存 → OOM
  需要 prefetch 限流兜底

纯拉的缺点（Kafka 的形态）：
  没消息时空拉 → 高频空轮询 → 浪费网络 CPU
  消费及时性差（要靠缩短轮询间隔换）

RocketMQ 取巧：
  消费者拉，Broker 没消息就【挂起请求等一会儿】
    → 有消息立刻返回（及时性 ✓）
    → 没消息挂到超时（避免空轮询 ✓）
    → 消费节奏由消费者自己控制（不会被打爆 ✓）
```

---

## 四、长轮询机制（核心）

```
Consumer 发起 pull
  ├─ Broker 有消息 → 立即返回
  └─ Broker 没消息 → 请求挂起到 PullRequestHoldService
       ├─ 等待期间有新消息到达 → 唤醒，立即返回
       └─ 等到超时（默认 15s）→ 返回空，Consumer 发起下一轮
```

**效果：有消息秒级投递（像推），没消息不空转（像拉），两头的好处都占了。**

---

## 五、Push 模式 vs Pull 模式

| 维度 | Push（DefaultMQPushConsumer） | Pull（DefaultLitePullConsumer） |
|------|------------------------------|--------------------------------|
| 本质 | 框架后台线程持续拉 | 自己手动拉 |
| 消费节奏 | 框架控制，有消息就处理 | **自己控制**，想拉才拉 |
| 位点提交 | 框架自动提交 | 自己管理 offset |
| 负载均衡 | 框架自动 Rebalance | 自己处理 |
| 复杂度 | 低，开箱即用 | 高，全手动 |
| 失败重试 | 框架自动进 %RETRY% | 自己实现 |

---

## 六、使用场景

### 用 Push（默认，覆盖 90%）

```
① 常规业务：下单、扣款、发积分——有消息就尽快处理
② 想要框架托管：自动位点提交、自动重试、自动负载均衡
③ 团队不想在消费细节上花精力
```

### 用 Pull（需要精确控制的场景）

| 场景 | 为什么必须 Pull |
|------|----------------|
| **攒批处理** | 等凑够 N 条再一起处理，提升批量 IO 效率 |
| **消费限流/流控** | 下游扛不住，按自己节奏拉，而不是消息一股脑进来 |
| **位点精确控制** | 回溯重放：把 offset 拨回去重新消费历史消息 |
| **延迟消费** | 业务想要"现在不处理，过会儿再拉" |
| **运维/监控工具** | 查看队列堆积情况、抽样消费，不需要持续订阅 |

---

## 七、面试话术（30 秒）

> RocketMQ 底层只有拉模式，Broker 从不主动推。所谓 PushConsumer 是框架把拉封装成长轮询——消费者拉取时 Broker 没消息就挂起请求等 15 秒，期间有新消息立刻唤醒返回，既保证及时性又避免空轮询，这就是"拉 + 长轮询伪装推"。选型上：常规业务用 Push，框架托管位点提交和重试，开箱即用；需要攒批、限流、位点回溯、延迟消费这类"节奏要自己控制"的场景才用 Pull。对比看：RabbitMQ 是真推要 prefetch 防打爆，Kafka 是纯拉要空轮询换及时性，RocketMQ 长轮询两头兼顾。

---

## 八、完整代码案例

### 8.1 Push 模式（DefaultMQPushConsumer）

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class PushConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("push-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("push-topic", "*");

        // 框架后台线程持续长轮询拉取，有消息就回调
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                System.out.println("Push 收到: " + new String(msg.getBody()));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
    }
}
```

### 8.2 Pull 模式（DefaultLitePullConsumer）

```java
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.List;

public class PullConsumer {
    public static void main(String[] args) throws Exception {
        DefaultLitePullConsumer consumer = new DefaultLitePullConsumer("pull-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("pull-topic", "*");
        consumer.start();

        while (true) {
            // 自己控制节奏：想拉才拉，可攒批、可限流
            List<MessageExt> msgs = consumer.poll();
            if (msgs.isEmpty()) {
                Thread.sleep(1000);   // 没消息自己决定等待多久
                continue;
            }
            for (MessageExt msg : msgs) {
                System.out.println("Pull 拉到: " + new String(msg.getBody()));
                // 可手动 seek 回溯位点：consumer.seek(new MessageQueue(...), offset);
            }
            // 位点提交自己控制
        }
    }
}
```

### 8.3 选型速记

| | Push | Pull |
|---|------|------|
| 核心类 | `DefaultMQPushConsumer` | `DefaultLitePullConsumer` |
| 拉取节奏 | 框架控制 | `poll()` 自己控制 |
| 位点提交 | 自动 | 手动（可 seek 回溯） |
| 适用 | 常规业务，开箱即用 | 攒批、限流、回溯、延迟消费 |
