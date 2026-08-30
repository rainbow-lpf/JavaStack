# RocketMQ 延迟消息原理

> 从"18 个延迟级别"到"换 Topic 障眼法"，把延迟消息的实现讲透

---

## 〇、快递场景先理解

```
延迟消息 = 预约寄件：现在交给快递站，但要求【过 30 分钟才允许朋友取】。

流程：
  ① 你把包裹交给快递站，但贴了个"暂不派送"的标签
     → 包裹进了【预约货架】（SCHEDULE_TOPIC_XXXX），朋友正常查件查不到
  ② 快递站有个值班员（ScheduleMessageService）盯着预约货架
  ③ 30 分钟一到 → 值班员把包裹从预约货架挪到正常货架，撕掉标签
     → 朋友现在能查到了
  ④ 到点前朋友查件 → "没有你的包裹"（物理上确实不在正常货架）

对应 RocketMQ：
  预约货架 = SCHEDULE_TOPIC_XXXX（延迟消息的独立存放处）
  值班员   = ScheduleMessageService 定时任务
  挪货架   = 到点把 Topic 改回真实值、重新写入 CommitLog
```

---

## 一、什么场景用

```
① 下单 30 分钟未支付 → 自动关单、回补库存
② 支付成功 15 分钟后 → 发"待评价"提醒
③ 消息投递失败 → 延迟重试（10s/30s/1m...）
④ 定时任务（RocketMQ 5.x 的定时消息就是延迟消息的升级版）
```

核心诉求：**消息不是立刻可见，而是"到点才可见"。**

---

## 二、两个版本的能力差异

| | 4.x | 5.x |
|---|-----|-----|
| 延迟粒度 | **18 个固定级别** | 任意时间点（定时消息） |
| 级别定义 | `1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h` | 秒级任意，基于时间轮 |
| 使用 | `setDelayTimeLevel(3)`（第 3 档 = 10s） | `setDeliverTimeMs(时间戳)` |

**4.x 的坑**：只能选 18 档之一，想要 45 秒？没有，只能选 30s 或 1m——这是面试常问的局限。

---

## 三、原理：还是"换 Topic"这招

参考事务消息的"暂时不可见 = 先存别的 Topic"，延迟消息是**同款障眼法**：

```
发送 30s 延迟的消息：
① Producer 把 Topic 换成 SCHEDULE_TOPIC_XXXX（延迟队列，消费者不可见）
   原 Topic 存进消息属性 REAL_TOPIC
② Broker 收到，正文照常写 CommitLog，索引落进 SCHEDULE_TOPIC 的 ConsumeQueue
   → 消费者拉业务 Topic，物理上翻不到这条消息 ✅ 不可见
③ Broker 内部 ScheduleMessageService 定时任务扫延迟队列
   → 到点了 → 把消息读出来 → Topic 改回真实值 → 重新写入 CommitLog
   → 索引落到业务 Topic → 消费者就能拉到了 ✅ 可见
```

```
时间线：
Producer                 Broker                      Consumer
   │                      │                            │
   ├─ 发延迟消息 ───────→ │ 索引进 SCHEDULE_TOPIC       │
   │  (topic 被换掉)       │                            │
   │                      │                            │
   │              ... 30s 后 ...                        │
   │                      │ ScheduleMessageService 到点 │
   │                      │ topic 改回 → 重写 CommitLog │
   │                      │ ──────── 消息 ───────────→ │ 到点才看见
```

**一句话：延迟消息是"时间到了搬回来"，事务消息是"事务确认了搬回来"——同一套换 Topic 机制。**

---

## 四、存储细节（4.x 的延迟级别怎么落地）

```
SCHEDULE_TOPIC_XXXX 本身按延迟级别又分队列：
  queueId = 延迟级别 - 1
  第 1 档(1s)  → queue0
  第 3 档(10s) → queue2
  ...
  第 18 档(2h) → queue17

ScheduleMessageService 定时扫描：
  - 每个级别一个定时线程，按级别对应的频率轮询对应 queue
  - 消息的"到期时间"存在消息属性 tagsCode 里（存到期时间戳）
  - 到点 → 取出 → 恢复真实 Topic → 重新投递
```

---

## 五、和定时消息（5.x）的关系

### 5.1 为什么会出现定时消息？

延迟队列的硬伤，靠档位解决不了：

```
① 档位太死：业务要 7s / 45m / 3 天 / 14:30:00 准时 → 不在档位表里
   改 messageDelayLevel 是全局的，改完全体 broker 重启、所有业务跟着动
② 上取整误差大：延迟消息按档位取上整，想延迟 5 分钟，下一档可能是 6m/7m
   长延迟下误差能到小时级，很多业务忍不了
③ 精度天花板：ScheduleMessageService 轮询 18 个队列，天生做不到任意时间点
④ 5.x 重写存储层的契机，顺手补齐这个缺口
```

**一句话：延迟队列是"档位轮询"，定时消息是"时间轮驱动"。**
出现定时消息，就是为了把"只能挑档位"升级成"任意时间戳、秒级精确"。

### 5.2 定时消息实现原理（时间轮 + TimerLog）

核心三件套：**TimerWheel（时间轮）+ TimerLog（定时日志）+ 异步投递管线**。

```
写入（Enqueue）：
  producer 发 setDeliverTimeMs 的定时消息
    ↓
  ① 消息正文 append 到 TimerLog（独立文件，类似 mini CommitLog）
  ② 时间轮对应到期时刻的 slot 记下 firstPos/lastPos/num
     → slot 只存指针，正文在 TimerLog，不占内存

触发（Dequeue）：
  TimerDequeueGetService 每 precisionMs（默认 1s）推进一格
    ↓
  currReadTimeMs 对应的 slot → 沿 TimerLog 链读出到期消息

投递：
  读出的消息 convert 回业务 topic
    ↓
  重新 putMessage 写进正常 CommitLog + 业务 ConsumeQueue
    ↓
  消费者此时才可见；同时写删除标记到 TimerLog，slot num -1
```

时间轮本质是 **mmap 环形数组**：
- `slotsTotal = 7 天 × 86400 秒`（`TIMER_WHEEL_TTL_DAY = 7`），一 slot 一精度单位
- slot 存 `timeMs + firstPos + lastPos + num`，正文在 TimerLog
- 环形复用：`getSlotIndex = timeMs / precisionMs % (slotsTotal * 2)`，写满转回覆盖 → 默认最长 7 天
- 保留 `TIMER_BLANK_SLOTS = 60` 空槽做滚动缓冲窗，只有当前时间之前的 slot 才会被回收
- 持久化恢复：TimerCheckpoint + TimerLog magic 校验，重启后 `recoverAndRevise` 重建索引

### 5.3 为什么先落 TimerLog，再写 CommitLog？

```
RocketMQ 可见性规则：消息写 CommitLog + 业务能拉

定时消息要"现在不可见，到点才可见"
  → 一进来就写 CommitLog 会让消费者马上消费，延迟失效
  → 所以必须先藏进消费者看不到的地方，到点再搬到看得到的地方
```

| | 延迟队列 | 定时消息 |
|---|---------|---------|
| 第①步暂存 | 写 `SCHEDULE_TOPIC_XXXX`（复用 CommitLog，靠换 topic 名藏） | 写独立 TimerLog 文件 |
| 第②步到期 | 读出来、topic 改回业务 topic、再写一遍 CommitLog | 读出来、写一遍 CommitLog |
| 双份位置 | CommitLog 里两份 | TimerLog 一份 + CommitLog 一份 |

两者本质一样：**"先藏起来 → 到点搬回业务 topic"**，都是双份写入。
区别只在"藏"的地方：延迟队列藏进 CommitLog 的系统 topic，定时消息藏进独立 TimerLog。
TimerLog 那份到期投递后会被过期清理（`deleteExpiredFileByOffsetForTimerLog`），双份只是临时重叠。

### 5.4 业务怎么选？

| | 延迟队列（delayLevel） | 定时消息（Timer，5.x） |
|---|----------------------|----------------------|
| 指定方式 | 传档位 `setDelayTimeLevel(3)` | 传时间戳 `setDeliverTimeMs(...)` |
| 精度 | 18 档离散，上取整 | 任意毫秒，秒级精确 |
| 版本 | 4.x 就有 | 5.x 新增 |
| 复杂度 | 简单、成熟、开销小 | 时间轮+TimerLog 多线程管线，复杂 |

```
用延迟队列（档位够用就选它）：
  - 延迟时间是固定常见值：订单 30m 关单、支付 1m/5m 重试、消费失败延迟重试
  - 追求简单、兼容 4.x、能接受上取整误差

用定时消息（要任意精确时间）：
  - 延迟值不在档位表：7s、45m、3 天
  - 要秒级精确：活动 14:30:00 准时开抢、拍卖截止、倒计时解锁
```

**工程取舍：不是"越灵活越好"，是"刚好满足需求、复杂度最低"。**
能用档位解决就优先延迟队列；精度和任意时间点成为硬需求才上定时消息。

---

## 六、可靠性注意点

```
① 延迟消息也走 CommitLog，正常刷盘/主从机制生效
② 重新投递是一次"新写入"——和事务消息 Commit 一样，是再写一条不是改原消息
③ 原延迟消息本体还在 SCHEDULE_TOPIC 里，靠逻辑标记/文件过期清理
④ 到点重投后如果消费失败 → 走普通重试机制（%RETRY%）
```

---

## 七、面试话术（30 秒）

> 延迟消息解决"到点才可见"。原理和事务消息同款——发送时把 Topic 换成内部的 SCHEDULE_TOPIC_XXXX，原 Topic 存属性里，消费者物理上拉不到；Broker 的 ScheduleMessageService 定时扫描，到点后把 Topic 改回、重新写入 CommitLog，消费者就能消费了。4.x 只有 18 个固定延迟级别（1s~2h），5.x 升级成任意时间的定时消息，底层用时间轮。一句话：延迟消息是"时间到了搬回来"，事务消息是"事务确认了搬回来"。

---

## 八、完整代码案例

### 8.1 延迟消息（4.x 档位）

```java
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

public class DelayProducer {
    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("delay-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();

        Message msg = new Message("delay-topic", "order", "30秒后关单".getBytes());
        // 第 4 档 = 30s（档位从 1 开始，不是 0）
        msg.setDelayTimeLevel(4);
        SendResult result = producer.send(msg);

        System.out.println("延迟消息已发送, 约 30 秒后投递");
        producer.shutdown();
    }
}
```

18 个档位对照：

```
level: 1    2    3    4    5    6    7    8    9    10   11   12   13   14   15   16   17   18
延迟:  1s   5s   10s  30s  1m   2m   3m   4m   5m   6m   7m   8m   9m   10m  20m  30m  1h   2h
```

### 8.2 定时消息（5.x 任意时间）

```java
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

public class TimerProducer {
    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("timer-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();

        Message msg = new Message("timer-topic", "order", "7秒后投递".getBytes());
        // 5.x：传精确时间戳，任意延迟，不走档位
        msg.setDeliverTimeMs(System.currentTimeMillis() + 7_000L);
        SendResult result = producer.send(msg);

        System.out.println("定时消息已发送, 精确 7 秒后投递");
        producer.shutdown();
    }
}
```

### 8.3 Consumer（两种消息共用普通消费者）

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class DelayConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("delay-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("delay-topic", "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                System.out.println("到点收到: " + new String(msg.getBody()));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
    }
}
```
