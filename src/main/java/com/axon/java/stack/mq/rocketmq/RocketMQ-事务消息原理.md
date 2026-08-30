# RocketMQ 事务消息原理

> 从"本地事务和消息发送的原子性难题"到"半消息 + 回查"的完整实现

---

## 〇、快递场景先理解

```
事务消息 = 寄快递前，包裹先放快递站【暂存柜】，朋友还取不到。

流程：
  ① 你把包裹放进暂存柜（半消息）——朋友那边系统里根本查不到这个包裹
  ② 你去办寄件事务：比如先去银行确认扣款成功（本地事务）
     ├─ 扣款成功 → 通知快递站"正式寄出"（Commit）→ 包裹从暂存柜挪到正常货架，朋友能取
     └─ 扣款失败 → 通知快递站"取消"（Rollback）→ 包裹从暂存柜撤下，谁也不见
  ③ 你迟迟没去银行确认（宕机了）→ 快递站主动打电话问你：
     "你那笔扣款到底成没成？"（回查）→ 按你的答复决定寄出还是取消

对应 RocketMQ：
  暂存柜 = RMQ_SYS_TRANS_HALF_TOPIC（半消息的独立存放处，消费者物理上拉不到）
  扣款   = 本地事务
  打电话 = Broker 回查 Producer
```

---

## 一、要解决的问题

```
用户下单：
① 扣库存（本地 DB 事务）
② 发消息通知积分系统（MQ）

两个动作跨资源，怎么保证原子？
  先发消息再扣库存 → 扣库存失败，消息已发 → 积分白加 ❌
  先扣库存再发消息 → 消息没发出去 → 积分漏加 ❌
```

**目标：本地事务成功 ⟺ 消息最终投递；本地事务失败 ⟺ 消息最终不投递。**

---

## 二、两阶段流程

```
第一阶段：预提交（Half Message 半消息）
   Producer 发半消息 → Broker 存着，但消费者【看不到】

第二阶段：执行本地事务
   执行扣库存
     ├─ 成功 → 发 Commit   → 半消息转正，消费者可见
     └─ 失败 → 发 Rollback → 半消息删除

兜底：Broker 长时间没收到 Commit/Rollback
   → Broker 主动反查 Producer："你本地事务成没成？"
   → 按回查结果 Commit 或 Rollback
```

---

## 三、核心追问：半消息为什么消费者看不见？

**答案：半消息根本没存进业务 Topic，而是存进内部系统 Topic——物理隔离。**

### 存储结构（CommitLog 正文不分组，ConsumeQueue 索引才分组）

```
store/
├── commitlog/                       ← 所有 Topic 正文混写，纯追加
│   #100 [topic=order_topic]     订单消息正文
│   #200 [topic=RMQ_SYS_TRANS_HALF_TOPIC]  半消息正文 ← 也混在这
│   #900 [topic=order_topic]     (Commit 后新写入)
│
└── consumequeue/
    ├── order_topic/                ← 消费者只翻这个目录
    │   ├── queue0: #100
    │   └── queue1: #900            ← Commit 后才有索引
    └── RMQ_SYS_TRANS_HALF_TOPIC/   ← 半消息索引在这，没人订阅
        └── queue0: #200
```

**可见性 = 索引落在哪个 Topic 的 ConsumeQueue 里。**

### Producer 端的"换信封"障眼法

```
producer.sendMessageInTransaction(msg, null) 内部：
① 把真实 Topic/队列记进消息属性：
     REAL_TOPIC    = "order_topic"
     REAL_QUEUE_ID = 1
② 把 msg.topic 改成 RMQ_SYS_TRANS_HALF_TOPIC
③ 正常发送 → 索引落 half topic 目录 → 消费者物理上拉不到
```

### Commit 是"重新投递"，不是"解锁"

```
EndTransactionProcessor 处理 Commit：
① 按消息 ID 从 half topic 找到半消息
② Topic 改回 REAL_TOPIC、队列改回 REAL_QUEUE_ID
③ 【重新写入 CommitLog 一遍】→ 索引落 order_topic → 消费者可见
④ 往 RMQ_SYS_TRANS_OP_HALF_TOPIC 写一条 op 标记 → "这条已处理，回查时跳过"
```

**Commit 后原来的半消息删了吗？没删。**

```
CommitLog 是 append-only，抠掉中间一条要挪动后面全部数据 → 不做
物理删除只有一种：按文件（默认 1GB/个）过期清理（72h）
半消息本体还在，只是被 op 标记逻辑删除
```

---

## 四、回查机制（兜底）

### 谁来查

```
TransactionalMessageCheckService 定时扫 half topic
  half topic: #200, #201, #202, #205...
  op topic:   #200, #201
  diff        → #202、#205 未决 → 触发回查
```

### 达到最大回查次数后怎么办（必考）

```
transactionCheckMax 默认 15 次
超过 15 次还没确定 → Broker 丢弃该半消息（不再投递）

"丢弃"不是物理删除：
  CommitLog 只追加，从不物理删
  ① half topic 该队列消费位点前移，跳过这条消息 → 不再回查
  ② 半消息转储到系统 Topic TRANS_CHECK_MAX_TIME_TOPIC
     （注意：日志里打印的是 TRANS_CHECK_MAXTIME_TOPIC，少一个下划线，
       实际 Topic 名是 TRANS_CHECK_MAX_TIME_TOPIC）
     → 留一个"丢弃兜底队列"，可追溯、可人工补偿
     → 转储时保留原业务 tag；"d" 是 OP 半 topic 的删除标记，不是丢弃 topic 的 tag
  真正的物理消失只有一种：CommitLog 文件过期清理（默认 72h）
  触发丢弃有两种条件：
    a. 回查次数 >= transactionCheckMax（默认 15）
    b. 消息年龄超过 fileReservedTime（72h）→ needSkip 也走丢弃转储

后果：本地事务和消息投递的绑定关系断了 → 必须业务侧兜底
排查步骤：
  ① 控制台查消息，看是否卡在 RMQ_SYS_TRANS_HALF_TOPIC
  ② 查 Broker 日志：搜 check transaction / discard 日志
     （关键字：TRANS_CHECK_MAXTIME_TOPIC）
  ③ 捞取丢弃队列：mqadmin printMsg -t TRANS_CHECK_MAX_TIME_TOPIC -c false
     从消息属性 REAL_TOPIC / KEYS 对账
  ④ 根因大概率是：checkLocalTransaction 查不到本地事务状态
     → 事务执行结果没持久化（没状态表）→ 回查答不上来
  ⑤ 兜底：本地事务状态表 + 定时对账补发
     重投前必须先确认本地事务真 commit，否则制造重复消息
```

**核心教训：回查能工作的前提是"能回答本地事务成没成"——事务结果必须落库。**

---

## 五、事务消息是最终一致性，不是强一致性

```
它保证的原子性：
  本地事务成功 ⟺ 消息最终投递；本地事务失败 ⟺ 消息最终不投递
  ↑ 注意"最终"两字

不一致窗口：
  T1 半消息已存，本地事务未执行  → 不可见，正常
  T2 本地事务已提交，Commit 未发  → 事务成了但下游还不知道 ★ 窗口
  T3 Commit 到达 → 消息可见 → 一致
  T2' Commit 丢失/宕机 → 靠回查兜底，迟到但会到
```

强一致（2PC/XA）要求所有人确认前都锁资源——吞吐崩；RocketMQ 反着来，靠"事后补偿（回查）"收敛，所以是最终一致。

---

## 六、面试话术（30 秒）

> 事务消息解决"本地事务和消息发送的原子性"：两阶段——先发半消息（消费者不可见），执行本地事务，成功 Commit 失败 Rollback；长时间未决 Broker 反查。半消息不可见的实现是"换 Topic"：Producer 把 Topic 偷换成 RMQ_SYS_TRANS_HALF_TOPIC，索引落系统 Topic 目录，消费者物理上拉不到；Commit 时把半消息读出来、恢复真实 Topic、重新写一遍 CommitLog，消费者才能看见。回查默认最多 15 次，超限 Broker 丢弃半消息，所以本地事务结果必须持久化供回查。本质是最终一致，靠回查收敛不一致窗口。

---

## 七、完整代码案例

```java
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionProducer {

    // ★ 本地事务结果必须落库/落内存，回查才答得上来
    private static final Map<String, Boolean> TRANS_RESULT = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        TransactionMQProducer producer = new TransactionMQProducer("tx-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.setTransactionListener(new TransactionListener() {

            // 回查：Broker 来问"本地事务成没成"
            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                String orderId = msg.getKeys();
                Boolean committed = TRANS_RESULT.get(orderId);
                if (committed == null) {
                    return LocalTransactionState.UNKNOW;   // 还不知道，下次再查
                }
                return committed
                        ? LocalTransactionState.COMMIT_MESSAGE
                        : LocalTransactionState.ROLLBACK_MESSAGE;
            }

            // 执行本地事务，返回状态
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                String orderId = msg.getKeys();
                try {
                    boolean success = doLocalTransaction(orderId);   // 扣库存等本地事务
                    TRANS_RESULT.put(orderId, success);              // ★ 结果持久化
                    return success
                            ? LocalTransactionState.COMMIT_MESSAGE
                            : LocalTransactionState.ROLLBACK_MESSAGE;
                } catch (Exception e) {
                    return LocalTransactionState.UNKNOW;            // 异常交给回查
                }
            }
        });
        producer.start();

        Message msg = new Message("tx-topic", "order", "order123", "积分消息".getBytes());
        producer.sendMessageInTransaction(msg, null);
        System.out.println("事务消息已发送");
    }

    private static boolean doLocalTransaction(String orderId) {
        // 扣库存等真实业务逻辑
        return true;
    }
}
```

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class TransactionConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("tx-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("tx-topic", "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                // 只有 Commit 后的消息才会到这里
                System.out.println("收到事务消息: " + new String(msg.getBody()));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        System.out.println("事务消费者已启动");
    }
}
```

### 关键点

| 状态 | 含义 |
|------|------|
| `COMMIT_MESSAGE` | 本地事务成功，消息转正，消费者可见 |
| `ROLLBACK_MESSAGE` | 本地事务失败，消息删除 |
| `UNKNOW` | 不确定，Broker 稍后回查 `checkLocalTransaction` |

**三个状态分别对应快递站的"寄出、撤单、待确认"。**
