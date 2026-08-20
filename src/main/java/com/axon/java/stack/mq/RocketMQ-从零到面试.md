# RocketMQ 从零到面试 — 完整指南

> 适用：第一次接触 MQ 的初学者

---

## 第一章：RocketMQ 整体架构 — 三个角色一栋楼

### 1.1 用快递来理解

```
你想寄快递给朋友。

没有快递公司：你得自己跑去他家，敲门，递东西。如果人不在，白跑一趟。

有快递公司（RocketMQ）：
你（Producer）→ 把包裹交快递站 → 快递站帮你存着 → 朋友（Consumer）有空去取
                                 ↑
                     如果快递站太多，得有个
                     地址本（NameServer）告诉你哪个站收这个包裹
```

### 1.2 RocketMQ 四个角色

```
NameServer（地址本）──────── 记录：Topic=订单 → 哪个 Broker 负责
    │
    ├── Producer（寄件人）→ 查 NameServer → 发消息给 Broker
    │
    └── Consumer（收件人）→ 查 NameServer → 从 Broker 拿消息

Broker（快递站）→ 真实的存消息的地方
```

| 角色 | 比喻 | 功能 |
|------|------|------|
| **NameServer** | 地址本 | 记录"哪个 Topic 在哪个 Broker"，轻量无状态，多个共存 |
| **Broker** | 快递站 | 存储消息，主从架构。Master 负责收发，Slave 负责备份 |
| **Producer** | 寄件人 | 发消息到指定 Topic |
| **Consumer** | 收件人 | 从指定 Topic 拿消息消费 |

---

## 第二章：Topic / MessageQueue / ConsumerGroup — 核心三兄弟

### 2.1 比喻

```
Topic = 快递类型（订单件）
    ├─ MessageQueue-0  →  放在一号快递柜
    ├─ MessageQueue-1  →  放在二号快递柜
    ├─ MessageQueue-2  →  放在三号快递柜
    └─ MessageQueue-3  →  放在四号快递柜

ConsumerGroup-A = 取件小分队（订单处理队的）
    ├─ 小王 → 负责一号、二号柜子
    └─ 小李 → 负责三号、四号柜子

ConsumerGroup-B = 另一个取件大队（数据同步队的）
    └─ 自己能拿全部四个柜子的件
```

### 2.2 三条铁律

```
铁律 1：同一个 ConsumerGroup 内，每条消息只被一个 Consumer 消费。
         → 小王拿了一号柜的件，小李就不会重复拿。

铁律 2：不同 ConsumerGroup 各自独立消费。
         → A 组取完，B 组还能再取一遍，互不干扰。

铁律 3：一个 Queue 最多被组内一个 Consumer 消费。
         → 如果有 5 个 Consumer 但只有 4 个 Queue，第 5 个闲死。
```

### 2.3 顺序消息怎么来？

```
问题：订单 "先付款后发货"，不能反过来。

解法：同一个订单号的消息发到同一个 Queue。
     一个 Queue 内消息天然有序。
     该 Queue 由一个 Consumer 单线程顺序消费。

├─ Queue-0: [订单1-付款] → [订单1-发货]  ← 有序！
├─ Queue-1: [订单2-付款] → [订单2-发货]  ← 有序！
└─ Queue-2: [订单3-付款]                 ← 来啥处理啥
```

```java
// Producer 确保同一订单进同一 Queue
SendResult result = producer.send(
    new Message("order-topic", "order123", body),  // order123 是业务 ID
    (mqs, msg, arg) -> mqs.get(Math.abs(arg.hashCode()) % mqs.size()),
    "order123"   // 按 order123 hash → 同一 Queue
);
```

---

## 第三章：事务消息 — "先暂存，确定没事再发出去"

### 3.1 问题场景

```
用户下单：
① 扣库存（DB 操作）
② 发消息给积分系统（MQ 操作）

如果先发消息再扣库存 → 库存扣失败，消息已经发了，积分系统白白加积分。
如果先扣库存再发消息 → 库存扣了，消息没发出去，积分系统啥也没收到。
```

### 3.2 RocketMQ 的两阶段提交

```
第一阶段：预提交（Half Message）
   Producer 发一条半消息给 Broker → Broker 存着，但消费者看不到
   
第二阶段：执行本地事务
   Producer 执行扣库存（本地事务）
     ├─ 成功 → 发 Commit 给 Broker → 半消息变成正常消息，消费者可见
     └─ 失败 → 发 Rollback 给 Broker → 半消息被删除
     
兜底：Broker 长时间没收到 Commit/Rollback
   → Broker 主动问 Producer："你那个半消息的本地事务，到底成没成？"
   → Producer 回查本地事务状态 → Broker 决定 Commit 还是 Rollback
```

```
时间线：
Producer                   Broker                    Consumer
   │                         │                          │
   ├── 发半消息 ──────────→ │ 存半消息（消费者不可见）
   │                         │
   ├── 执行本地事务（扣库存）  │
   │   成功！                 │
   │                         │
   ├── 发 Commit ──────────→ │ 半消息 → 正常消息 ──→ Consumer 可见
   │                         │
```

### 3.3 追问：半消息为什么消费者看不见？

面试官常追问 3.2 的第一阶段："Broker 存着，但消费者看不到" —— 到底怎么做到的？
答案一句话：**半消息根本没存到业务 Topic 里，而是被存进了 Broker 内部的系统 Topic，物理隔离。**

#### 第一层：消费者只查"自己的信箱"，不看所有消息

先看 Broker 磁盘的真实结构（**核心：正文不分组，索引才分组**）：

```
store/
│
├── commitlog/                        ← 所有 Topic 的正文混在同一个文件，追加写
│   └── 00000000000000000000             （为了磁盘顺序写，性能关键）
│       ┌──────────────────────────────────────────────────┐
│       │ #100 [topic=order_topic]            订单消息正文   │
│       │ #200 [topic=RMQ_SYS_TRANS_HALF_TOPIC] 半消息正文  │ ←★半消息的正文
│       │ #300 [topic=user_topic]             用户消息正文   │    也混在这里！
│       │ #900 [topic=order_topic] (Commit后新写入的)        │
│       └──────────────────────────────────────────────────┘
│
└── consumequeue/                     ← 按 Topic 建目录，里面只放索引(指针)
    │
    ├── order_topic/                      ← 消费者只翻这个目录 ★
    │   ├── queue0:  #100 ─┐
    │   └── queue1:  #900 ─┴─→ 指向 CommitLog 里的正文
    │
    ├── user_topic/
    │   └── queue0:  #300 ──→ 指向 CommitLog
    │
    └── RMQ_SYS_TRANS_HALF_TOPIC/         ← 半消息的索引在这
        └── queue0:  #200 ──→ 指向 CommitLog
              （没人订阅这个目录 → 消费者不可见）
```

消费者拉消息的真实动作：

```
消费者："我要拉 order_topic 队列0 的消息"
        ↓
Broker 翻 order_topic 的 ConsumeQueue 目录 → 取正文 → 返回
```

**关键结论：消息可见性取决于它的索引落在哪个 Topic 的 ConsumeQueue 里。**
所以"让消息不可见"最简单的办法不是打标记、不是加密，而是——存到另一个 Topic 里去。

> 比喻：想让别人看不见一份文件，不是在文件上写"别看"，而是先放进自己抽屉里，
> 确认要给对方了，再放到他桌上去。

#### 索引怎么和正文关联（ConsumeQueue 条目结构）

ConsumeQueue 里每条索引固定 **20 字节**，就三个字段：

```
┌─────────────────────┬──────────────┬───────────────────┐
│ 8 字节               │ 4 字节        │ 8 字节             │
│ CommitLog 物理偏移量  │ 消息总长度    │ Tag 的 hashcode    │
│ (offsetPy)          │ (sizePy)     │ (tagHash)          │
└─────────────────────┴──────────────┴───────────────────┘

"关联关系" = 偏移量 + 长度：
去 CommitLog 的第 offsetPy 个字节开始，读 sizePy 个字节 → 就是完整消息存储体
tagHash 给消费端过滤用：订阅了 tagA 的消费者，Broker 先比对 tagHash，
不匹配直接跳过，省得去 CommitLog 白读一遍正文
```

消费者一次 pull 的完整寻址链（两跳）：

```
消费者："我要 order_topic 队列0 的消息，我已消费到第 3 条"

第 1 跳：Topic+queueId → consumequeue/order_topic/queue0/ 目录
第 2 跳：消费进度 offset=3 → 3 × 20字节 = 第 60 字节处，读出 20 字节
         → [offsetPy=0x1F400, sizePy=236, tagHash]
第 3 跳：偏移量 → 去 commitlog 文件 0x1F400 位置读 236 字节 → 反序列化 → 返回
```

两个设计细节：

- **为什么定长 20 字节**：定长才能用乘法直接寻址（位置 = offset × 20），O(1) 定位。
  这也是消费者的"消费位点"就是一个整数的原因——一个 long 就能表达进度。
- **索引是异步写的**：消息追加进 CommitLog 就给 Producer 返回成功，后台线程
  （ReputMessageService）再解析新消息、往对应 Topic 目录补索引（毫秒级延迟）。

#### 第二层：发送时被"偷换收件地址"（障眼法在 Producer 端）

```
代码：producer.sendMessageInTransaction(msg, null)

Producer 客户端内部（真正发送之前）：
  ① 把原 Topic/队列塞进消息属性：
        REAL_TOPIC    = "order_topic"     ← 记下真实目的地
        REAL_QUEUE_ID = 1
  ② 把 msg.topic 改成 "RMQ_SYS_TRANS_HALF_TOPIC"  ← 换成内部信箱
  ③ 正常发送

Broker 收到后：
  一看 Topic 是 RMQ_SYS_TRANS_HALF_TOPIC → 按普通消息正常存储
  → 索引落在 half_topic 的 ConsumeQueue 目录里
```

反直觉的点：**Broker 没做任何"隐藏"动作，它是被动配合的**。
整个障眼法发生在 Producer 客户端 —— 发送前换了信封。

此时消费者拉 order_topic 的目录 → 里面根本没有这条消息的索引 → 完全不知道它存在。
不是"看见了但假装没看见"，是**物理上就翻不到**。
（双保险：`RMQ_SYS_` 开头的系统 Topic，普通消费者连订阅都会被拒绝。）

#### 第三层：Commit 时"重新投递"到真正的信箱

Broker 的 EndTransactionProcessor 处理 Commit，本质是一次重新投递：

```
① 根据 Commit 请求带的消息ID，去 half_topic 目录找到那条半消息
② 把 Topic 改回 REAL_TOPIC（order_topic），队列号改回 REAL_QUEUE_ID
③ 把这条消息【重新写入 CommitLog 一遍】
   → 这次索引落在 order_topic 的 ConsumeQueue 里
   → 消费者下次拉取就能拉到了
④ 往 RMQ_SYS_TRANS_OP_HALF_TOPIC 写一条 op 标记
   → "这条半消息已处理完，回查时跳过"
```

注意 ③：**Commit 不是"解锁原消息"，而是"照着半消息重新写一条正常消息"**。
半消息本体还躺在 half topic 里，只是被 op 标记记为已处理（逻辑删除）。
所以 Rollback 只需要做第 ④ 步 —— 写个标记"这条不要了"，因为它从没进过 order_topic。

**追问：Commit 之后，原来的半消息删了吗？**

```
没有删除。CommitLog 是纯追加(append-only)的——#200 前面是 #100、后面是 #300，
抠掉 #200 得挪动后面所有数据 = 磁盘随机写 + 大整理，RocketMQ 不干这种事。

Commit 后磁盘的真实状态：
  CommitLog:            #200（半消息正文）← 还在！
  half_topic 索引目录:   [#200]           ← 也还在！
  OP_HALF_TOPIC 目录:   [#200已处理]       ← 新增的标记，仅此而已
```

op 标记是给**回查服务**看的：`TransactionalMessageCheckService` 定时扫
half topic，和 op topic 做 diff——没有 op 标记的才是超时未决的，才回查 Producer：

```
half topic:  #200, #201, #202, #205...
op topic:    #200, #201
diff        → #202、#205 未决 → 触发回查
```

物理删除只有一种：**按文件过期清理**。CommitLog 以文件（默认 1GB/个）为单位，
文件里最早消息超过保留时间（fileReservedTime，默认 72 小时）才整文件删除。
这也是 RocketMQ 的通用哲学——几乎从不删单条消息，只做逻辑标记：
普通消息消费后也不删（靠消费位点推进），半消息处理完也不删（靠 op 标记）。

#### 第四层：延迟消息是同一招（佐证）

```
延迟消息（30秒后才可消费）：
发送前 Topic 被换成 SCHEDULE_TOPIC_XXXX → 存进延迟队列，消费者看不见
→ 定时任务到点，把 Topic 改回原值重新写入 → 消费者可见
```

RocketMQ 的设计哲学：**"暂时不可见" = 先存到别的 Topic，时机到了再搬回真正的 Topic。**
延迟消息是"时间到了搬回来"，事务消息是"事务确认了搬回来"。

#### 一张图串起整个流程

```
Producer                        Broker                          消费者(积分系统)
   │                              │                                │
   │ ①偷换信封：                   │                                │
   │   topic→HALF_TOPIC           │                                │
   │   属性记下 REAL_TOPIC         │                                │
   │ ─────── 半消息 ─────────→     │                                │
   │                              │ ②正文#200追加进CommitLog        │
   │                              │   索引→half_topic目录           │
   │                              │                                │
   │                              │ ←───── 拉order_topic消息 ──────│
   │                              │  翻order_topic索引目录:查无     │
   │                              │ ─────── "没有新消息" ─────────→ │ ★看不见
   │                              │                                │
   │ ③执行本地事务(扣库存)          │                                │
   │   成功!                      │                                │
   │ ─────── Commit ─────────→     │                                │
   │                              │ ④读出#200，topic改回order_topic │
   │                              │   作为新消息写入CommitLog #900  │
   │                              │   索引→order_topic目录          │
   │                              │ ⑤写op标记：#200已处理完          │
   │                              │                                │
   │                              │ ←───── 拉order_topic消息 ──────│
   │                              │  翻order_topic索引:发现#900     │
   │                              │ ─────── 消息正文 ────────────→ │ ★可见！
```

对照存储结构图看：#200 和 #900 两条正文都在 CommitLog 里，
区别只是索引一个落在 half_topic 目录、一个落在 order_topic 目录 —— 消费者只认目录。

#### 追问到底：为什么不用标记位实现不可见？

> 标记位意味着所有消费者拉取时都要过滤一遍，浪费性能且容易出错；
> 换 Topic 是零成本的物理隔离，索引层面天然不可达。

#### 一句话回答

> 消费者只拉自己订阅 Topic 的 ConsumeQueue 索引。Producer 发半消息前把 Topic 偷偷
> 换成内部的 RMQ_SYS_TRANS_HALF_TOPIC，真实 Topic 存在消息属性里，索引落在 half
> topic 目录下，消费者物理上拉不到。Commit 时 Broker 把半消息读出来、恢复真实
> Topic、重新写一遍 CommitLog，索引这才落到业务 Topic 里，消费者就"看见"了。

### 3.4 追问：事务消息是最终一致性还是强一致性？

**答案：最终一致性。**

它保证的原子性是这一条：

> **本地事务成功 ⟺ 消息最终会被投递；本地事务失败 ⟺ 消息最终不会投递。**

注意措辞——"最终"。中间存在明确的不一致窗口：

```
T1  半消息已存，本地事务还没执行     → 消息不该被看见，也确实看不见
T2  本地事务已提交，Commit 还没发出  → 事务成了，但下游还不知道  ★ 不一致窗口
T3  Commit 到达，消息可见            → 下游补齐状态            → 重新一致
T2' Commit 丢了 / Producer 宕机     → 靠 Broker 回查兜底，可能拖几秒到几分钟才一致
```

只要存在这个窗口、且一致性靠"事后补偿（回查）"来收敛，就是典型的最终一致性。

**为什么做不成强一致？** 强一致（XA / 2PC）要求任一参与方没确认之前所有人
都不能真正提交——资源全程锁住，吞吐暴跌。RocketMQ 处处反着来：

| 环节 | 强一致的做法 | RocketMQ 的实际做法 |
|------|------------|-------------------|
| 本地事务 | 协调者统一提交/回滚，DB 持锁等待 | Producer 自己先提交，不等 Broker |
| 半消息 | 对外不可见靠全局锁 | 靠换 Topic 物理隔离 |
| Commit 丢失 | 阻塞等待 | 回查兜底，迟到但会到 |
| 下游消费 | 所有参与方一起可见 | 消费者自己 pull，消费节奏它自己定 |

第 4 行尤其关键：即使消息已可见，消费者什么时候消费完，事务消息根本不管。
所以事务消息只覆盖"本地事务 ↔ 消息投递"这一段，整条链路是多层最终一致的叠加：

```
扣库存(本地事务) ──事务消息──→ 消息可见 ──pull/重试──→ 积分系统加积分
      ↑                          ↑                        ↑
   这一截：事务消息保证           这一截：MQ 自身保证        这一截：重试+幂等保证
  （最终一致）                 （可靠投递）              （又一层最终一致）
```

**一个诚实的补充（加分点）**：最终一致 ≠ 不会不一致，是"不一致窗口有界"。
回查要求 Producer 能回答"本地事务成没成"——所以必须把事务执行结果持久化
（本地事务状态表），或让回查去查真实业务状态（如订单表）。如果本地事务成功
但状态没记下来、回查又答不上来，Broker 只能按超时 Rollback → 真不一致了。

---

## 第四章：消息不丢 — 三道防线

```
Producer ──send──→ Broker ──pull──→ Consumer
    ↑                ↑                ↑
  防线1            防线2            防线3
```

### 防线 1：Producer → Broker

```java
// 同步发送 + 重试
producer.send(message, new SendCallback() {
    public void onSuccess(SendResult result) { /* 成功了 */ }
    public void onException(Throwable e) { /* 重试或记日志 */ }
});
```

### 防线 2：Broker 磁盘

```properties
# Broker 配置文件
flushDiskType = SYNC_FLUSH      # 同步刷盘 — 消息写到磁盘才返回成功
brokerRole = SYNC_MASTER        # 主从同步双写 — Slave 也写成功才返回
```

### 防线 3：Broker → Consumer

```java
consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
    for (MessageExt msg : msgs) {
        bizProcess(msg);         // 处理业务
    }
    return ConsumeOrderlyStatus.SUCCESS;  // 只有处理成功才确认
});
```

---

## 第五章：消息积压和幂等

### 5.1 消息堆了几百万条怎么办？

```
原因分析：Consumer 处理能力跟不上 Producer 发送速度。

解决方案（由快到慢）：
① 扩容 Consumer：增加 Consumer 实例 = Queue 数
② 降级处理：新 Consumer 只记日志不处理，先把消息"消费掉"
③ 迁移 Topic：积压消息灌到新 Topic，开 20 个 Queue，20 个临时 Consumer 并行消化
④ 排查原因：Consumer 里是否有慢 SQL、锁等待
```

### 5.2 消息重复了怎么办？（幂等）

**为什么消息会重复？**
> Producer 发完没 → 两条一样消息。
> Consumer 消费一半宕机了，Broker 没收到确认 → 重发给另一个 Consumer。

**解决方案：业务唯一 ID**

```java
public void handleOrderMessage(OrderMessage msg) {
    String orderId = msg.getOrderId();
    
    // 查 Redis：这个 orderId 是不是处理过了？
    if (redis.setNx("order:processed:" + orderId, "1", 60, TimeUnit.SECONDS)) {
        // 没处理过 → 处理
        processOrder(msg);
    } else {
        // 处理过了 → 直接跳过
        log.info("重复消息，跳过: " + orderId);
    }
}
```

| 方案 | 做法 |
|------|------|
| **Redis setNx** | key=业务ID，抢到锁才处理，过期自动删 |
| **DB 唯一键** | insert 时唯一键冲突 → 已处理过 |
| **业务状态** | 查订单状态，已处理过的跳过 |

### 5.3 追问：单条消息卡住，会阻塞队列造成堆积吗？

**答案取决于卡在哪一层——存储层永远不会，消费层分两种模式，答案相反。**

#### 存储层：永远不会被单条消息阻塞

```
消息卡住了？→ CommitLog 照样往后追加，索引照样往后追加
             新消息的写入和存储完全不受任何旧消息状态的影响

堆积的本质 = 队列的 maxOffset（写到哪）- consumerOffset（消费到哪）
           只是一个越来越大的差值，不存在"堵在管道口"这回事
```

没有链表、没有队列锁、没有"下一条必须等上一条"。这就是 append-only +
只增索引设计的红利——生产端永远畅通。

#### 消费层：并发模式不卡，顺序模式会卡

```
并发模式（MessageListenerConcurrently）—— 不卡：
消费失败 → 返回 RECONSUME_LATER
        → Broker 把这条消息转投重试 Topic：%RETRY%消费者组名
        → 原队列 offset 正常推进（同批其他消息成功就往前走）
        → 按延迟等级（10s、30s、1m、2m...）重投，最多 16 次
        → 还失败 → 进死信队列 %DLQ%消费者组名，人工处理
代价：消费顺序得不到保证，换吞吐。

顺序模式（MessageListenerOrderly）—— 真的会卡：
一个队列 = 一个线程逐条消费
某条失败 → SUSPEND_CURRENT_QUEUE_A_MOMENT → 原地等待重试同一条
★ 这条之后的消息全部排队等，该队列对该消费者组的 offset 停滞
这正是顺序语义的必然代价（跳过去就乱序了）。
即使这样，影响范围也只有：这一个队列 × 这一个消费者组。
```

#### 堆积真正的杀伤点：磁盘

单条消息卡住伤不到存储，但持续堆积会碰到另一堵墙：

```
CommitLog 文件清理条件（默认）：
  文件里最早消息 > 72小时 且 文件里的消息都消费完了 → 整个文件删除
                        ↑
        堆积的消息没消费完 → 文件不能删！
        ↓
堆积 → 旧文件删不掉 → 磁盘水位持续上升 → 撑爆磁盘 → Broker 拒收（真事故）
```

这也是 5.1 的堆积治理要趁早的底层原因。

#### 汇总

| 场景 | 队列会被卡吗 | 影响范围 |
|------|------------|---------|
| 单条消息消费失败（并发模式） | ❌ 转投 %RETRY%，offset 照常推进 | 只有那条消息延迟 |
| 单条消息消费失败（顺序模式） | ✅ 原地重试，后面全等 | 该队列、该消费者组 |
| 消费者整体处理慢 | ❌ 不是"卡"是"慢"，堆积量涨 | 该消费者组 |
| 生产端 | 永不受影响 | — |
| 存储 | append-only，永不被单条消息阻塞 | 极限是磁盘水位 |

---

## 第六章：面试速记

### 6.1 一句话

> **RocketMQ 是 Java 写的分布式消息队列，阿里出品。NameServer 做注册中心，Broker 主从存储，原生支持顺序消息和分布式事务。**

### 6.2 事务消息怎么回答？

> 两阶段：先发半消息（消费者不可见），再执行本地事务。成功就 Commit，失败就 Rollback。长时间未决 → Broker 反查生产者。

### 6.3 顺序消息怎么保证？

> 同一业务 key 发到同一 MessageQueue，Queue 内有序。一个 Consumer 单线程顺序消费该 Queue。

### 6.4 消息不丢？

> 三道防线：生产端同步发送+重试、Broker 同步刷盘+主从同步、消费端业务处理成功再确认。

### 6.5 幂等怎么搞？

> 业务唯一 ID + Redis setNx 判重。处理过的直接跳过。

---

## 第七章：关键词中英文对照

| 中文 | 英文 |
|------|------|
| 名称服务器 | NameServer |
| 消息队列 | MessageQueue |
| 消费者组 | ConsumerGroup |
| 主题 | Topic |
| 半消息 | Half Message |
| 事务消息 | Transactional Message |
| 幂等 | Idempotent |
| 刷盘 | Flush |
| 主从同步 | Master-Slave |
