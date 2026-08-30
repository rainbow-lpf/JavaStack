# RocketMQ 普通消息原理

> 从"发一条消息"到"消费一条消息"，把普通消息的全链路存储结构讲透

---

## 〇、快递场景先理解

```
你想寄快递给朋友。

没有快递公司：自己跑去他家敲门递东西。人不在 → 白跑一趟。

有快递公司（RocketMQ）：
你（Producer）→ 把包裹交快递站 → 快递站帮你存着 → 朋友（Consumer）有空去取
                                ↑
                     快递站太多，得有个地址本（NameServer）告诉你哪个站收这个包裹

普通消息 = 最朴素的"寄件 → 入站 → 取件"：
  包裹（消息）一入快递站（Broker），朋友（Consumer）随时能查到取走，
  不需要预约、不保证装箱顺序、不挑拣。它就是所有消息类型的底座。
```

---

## 一、什么算普通消息

没有顺序、事务、延迟等特殊语义的默认消息。`send()` 出去就是它。**它是一切消息类型的底座**——顺序/事务/延迟都是在普通消息的存储骨架上做文章。

```
普通消息 = 一发送就立刻对消费者可见，不排队、不隐藏、不分组保证
```

---

## 二、发送链路（Producer → Broker）

```
① Producer 从 NameServer 拿 Topic 路由信息（该 Topic 分布在哪些 Broker、每个 Broker 上该 Topic 有几个 Queue）
② 选一个 MessageQueue（默认轮询 + 失败规避故障队列）
③ 通过 Netty 发往 Broker
④ Broker 收到 → 消息正文追加写进 CommitLog（顺序写磁盘）
⑤ ReputMessageService 后台线程解析新消息 → 往该 Topic 的 ConsumeQueue 补一条索引
⑥ Broker 返回 SendResult（offset、queueId）
```

### 三种发送方式

| 方式 | 语义 | 用途 |
|------|------|------|
| 同步 send | 等 Broker 确认才返回 | 核心业务，要可靠 |
| 异步 send | 不等，回调通知 | 吞吐优先，可容忍延迟 |
| 单向 sendOneway | 不等待不确认 | 日志埋点，丢了也不心疼 |

---

## 三、存储结构：CommitLog + ConsumeQueue（核心）

**RocketMQ 存储的哲学：正文不分组，索引才分组。**

```
store/
├── commitlog/                      ← 所有 Topic 的正文混写一个文件，纯顺序追加
│   └── 00000000000000000000        （默认 1GB/个，写满滚动下一个）
│       #100 [topic=order]  订单消息正文
│       #200 [topic=user]   用户消息正文   ← 完全不同的 Topic 紧挨着
│       #300 [topic=order]  订单消息正文
│
└── consumequeue/                   ← 按 Topic + queueId 建目录，只放 20 字节定长索引
    ├── order/
    │   ├── queue0:  [#100, #300]   ← 指向 CommitLog 偏移
    │   └── queue1:  [...]
    └── user/
        └── queue0:  [#200]
```

### 为什么这么设计？

```
顺序写：所有消息无论 Topic 都追加到同一个文件尾部 = 磁盘顺序 IO，速度快一个量级
        如果按 Topic 分文件写，就是多个文件的随机 IO，性能崩

索引轻量：ConsumeQueue 每条 20 字节（offsetPy 8B + sizePy 4B + tagHash 8B）
          定长 → 消费位点用一个 long 表示，寻址 = offset × 20 直接定位 O(1)
```

### ConsumeQueue 条目 20 字节拆解

```
┌──────────────┬──────────────┬───────────────┐
│ 8 字节        │ 4 字节        │ 8 字节         │
│ CommitLog偏移 │ 消息长度      │ Tag hashcode   │
└──────────────┴──────────────┴───────────────┘
  消费者拿 offsetPy+sizePy 去 CommitLog 读正文；tagHash 用于订阅过滤
```

### CommitLog 消息结构（变长）

每条消息 = **固定头 91 字节 + 变长部分**：

```
┌─────────────────────────────────────────────┐
│ 4B  totalSize          总长度                 │
│ 4B  magicCode          魔数，校验用            │
│ 4B  bodyCRC            正文 CRC 校验           │
│ 4B  queueId            所属队列 id             │
│ 4B  flag               标志位                  │
│ 8B  queueOffset        队列内逻辑偏移（第几条）  │
│ 8B  physicalOffset     本消息物理偏移           │
│ 4B  sysFlag            系统标志（压缩/事务等）   │
│ 8B  bornTimestamp      生产时间戳              │
│ 8B  bornHost           生产端地址              │
│ 8B  storeTimestamp     存储时间戳              │
│ 8B  storeHostAddress   存储端地址              │
│ 4B  reconsumeTimes     重试次数                │
│ 8B  preparedTransactionOffset  事务预留        │
│ 4B  bodyLength         正文长度                │
├─────────────────────────────────────────────┤
│ bodyLength 字节        body 正文（业务数据）    │
│ 1B  topicLength        topic 名长度           │
│ topicLength 字节       topic 名字符串          │
│ 2B  propertiesLength   属性区长度              │
│ propertiesLength 字节  properties（KEYS/tag/自定义）│
└─────────────────────────────────────────────┘
```

### 两者关系：定长目录 + 变长正文

```
CommitLog（正文，变长，所有 topic 混写）
  [msg1][msg2][msg3]...        ← 每条长度不同 → 无法用"序号×定长"直接算

ConsumeQueue（索引，定长 20B，按 topic+queue 分目录）
  [20B][20B][20B]...           ← 每条一样长 → 序号×20 直接寻址
    │
    └─ 索引里 offsetPy 指向上面的某条 msg
```

一句话：ConsumeQueue 是给变长的 CommitLog 建的**定长目录**，实现 O(1) 寻址。

---

## 四、消费链路（Broker → Consumer）

```
① Consumer 启动 → 从 NameServer 拿到路由 + 分配 Queue（Rebalance）
② 拉模式：Consumer 主动 pull（长轮询，Broker 没消息挂起等一会儿）
③ Broker 翻该 Topic+queueId 的 ConsumeQueue → 按消费位点读索引 → 读正文 → 返回
④ Consumer 处理完 → 更新消费位点 offset（提交到 Broker 或本地定时上报）
```

### 关键追问：消费位点在哪？

```
消费进度 = 一个递增的 long（队列里已消费到第几条）
存在 Broker 端（集群模式）或本地（广播模式）
消息被消费后【不删除】——CommitLog 还留着，只是 offset 往前走
→ 这就是"消息可回溯"的原理：把 offset 拨回去重新拉
```

### 渐进理解：一次消费的完整读取顺序

**第 1 步：先分清三样东西，它们存三处地方**

```
store/                                ← Broker 数据目录
├── commitlog/       ① 消息正文（所有 topic 混写，变长）
│   └── 00000000000000000000
│       #100 [order]  订单A
│       #200 [user]   用户B
│       #300 [order]  订单C
│
├── consumequeue/    ② ConsumeQueue 索引（按 topic + queue 分目录，定长 20B/条）
│   ├── order/                  ← topic 一级目录
│   │   ├── queue0/             ← queueId 二级目录
│   │   │   └── 00000000000000000000   ← 该队列的索引文件（20B × N 条）
│   │   └── queue1/
│   │       └── 00000000000000000000
│   └── user/
│       └── queue0/
│           └── 00000000000000000000
│
└── config/
    └── consumerOffset.json   ③ 消费位点（每个 group 消费到第几条）
```

| 名称 | 本质 | 内容 | 类比 |
|------|------|------|------|
| CommitLog | 消息正文 | 完整消息字节 | 书库（书乱序堆） |
| ConsumeQueue | 索引文件 | 20B/条的指针 | 图书目录卡片 |
| consumerOffset.json | 消费进度 | `topic@group → {queueId: offset}` | 借阅记录（读到第几本） |

**第 2 步：consumerOffset.json 里存什么？**

```json
{
  "offsetTable": {
    "order-topic@order-consumer-group": {
      "0": 1024,     ← queue0 消费到第 1024 条
      "1": 3050      ← queue1 消费到第 3050 条
    }
  }
}
```

```
关键点：
  key   = topic@消费组名
  value = queueId → offset（一个数字，不是逐条记录）
  消费后是【数字 +1 覆盖】，不是 append 新记录
  刷盘时机：内存先更新，定期批量刷盘（默认几秒一次）+ 优雅停机刷
```

**第 3 步：为什么 ConsumeQueue 要 ×20 定位？**

先看 ConsumeQueue 一个队列的文件长什么样——就是"20 字节一条，一条紧挨一条"的纯字节流：

```
文件路径：consumequeue/order/queue0/00000000000000000000

字节偏移        内容
0  ~ 19    ┌──────────────────────┐  第 0 条索引（20 字节）
20 ~ 39    ├──────────────────────┤  第 1 条索引（20 字节）
40 ~ 59    ├──────────────────────┤  第 2 条索引（20 字节）
60 ~ 79    ├──────────────────────┤  第 3 条索引（20 字节）
...        ├──────────────────────┤
N×20 ~     ├──────────────────────┤  第 N 条索引（20 字节）
```

```
为什么"第 N 条"的位置能直接算出来？
  因为每条都一样长（20 字节），一个紧挨一个，中间没有空隙、没有变长内容
  → 第 N 条的起始位置 = N × 20 字节
  → 就像排成一条线的 20 厘米格子，第 5 个格子在 5×20=100 厘米处
```

用 Java 数组类比（本质一模一样）：

```java
int[] arr = new int[100];
// int 定长 4 字节
// arr[3] 的内存地址 = 数组首地址 + 3 × 4
// 不用遍历 arr[0]、arr[1]、arr[2]，一步跳到 arr[3]

// ConsumeQueue 同理：
// offset 就是数组下标，20 就是元素大小
// 第 5 条索引的位置 = 文件首地址 + 5 × 20
```

对比：如果每条长度不固定（像 CommitLog 那样变长），会怎样？

```
变长文件：
  [msgA 500字节][msgB 200字节][msgC 350字节]
  想找 msgC，得先读 msgA 知道它多长 → 跳过 → 再读 msgB 知道多长 → 再跳
  → 必须从头一条条数，O(n) 线性扫描，慢

定长文件（ConsumeQueue）：
  [20B][20B][20B]
  想找第 5 条 → 5×20=100 直接定位，O(1)，一步到位
```

这就是"定长"的价值：**位置 = 序号 × 长度**，随机访问不用扫。offset × 20 里的 20 就是"每条索引的固定字节数"。

补充：ConsumeQueue 文件也不是无限长，是**追加写 + 写满滚动**：

```
consumequeue/order/queue0/
├── 00000000000000000000     第 0 个文件（默认 600 万字节 = 30 万条 × 20B）
├── 0000000000060000000      第 1 个文件（下 30 万条）
└── ...
```

所以 offset × 20 算的是"全局逻辑位置"，broker 内部再拆成"第几个文件 + 文件内偏移"：

```
offset = 500000 条
  → 500000 × 20 = 10000000 字节（全局）
  → 落在第 1 个文件：10000000 ÷ 6000000 = 1
  → 文件内偏移：    10000000 % 6000000 = 4000000 字节
  → 打开第 1 个文件，从 4000000 字节处读 20 字节
```

跟 CommitLog 1GB 滚一个文件是同一个套路，只是文件大小不同（ConsumeQueue 默认 600 万字节）。

**第 4 步：为什么需要 ConsumeQueue 中转？不能直接 ×20 找 CommitLog？**

```
因为 CommitLog 是【变长 + 混写】：
  #100 [order] 500 字节
  #600 [user]  200 字节
  #800 [order] 350 字节
  ↑ order 的消息东一条西一条，长度还不一样 → 无法用"第 N 条 = N×定长"算

所以需要一个【定长目录】做翻译：
  逻辑 offset（第几条）→ 物理 offsetPy（CommitLog 第几字节）
```

**第 5 步：完整读取链路（串起来）**

```
Consumer                               Broker
   │ ① 从 consumerOffset.json 拿到 offset=5
   │     （topic=order, queueId=0）
   │
   │ ② PULL 请求：topic + queueId + offset=5
   │ ─────────────────────────────────────→
   │                                    ③ offset × 20 = 5×20 = 100 字节处
   │                                      定位 ConsumeQueue 索引（O(1)）
   │
   │                                    ④ 读 20 字节索引：
   │                                       commitLogOffset = 8000
   │                                       msgSize         = 350
   │
   │                                    ⑤ 按 8000 字节去 CommitLog
   │                                       读 350 字节 → 完整消息正文
   │
   │ ⑥ 返回消息 + nextBeginOffset=6
   │ ←─────────────────────────────────────
   │
   │ ⑦ 业务处理成功 → 上报 offset=6
   │ ─────────────────────────────────────→ ⑧ 内存 +1 覆盖，定期刷 json
```

**一句话链条：**

```
json 的 offset（第几条）
  → ×20 定位 ConsumeQueue 索引
  → 索引里 offsetPy 定位 CommitLog 字节位置
  → 读 msgSize 长度 = 消息正文
  → 消费成功 → 上报 offset+1 → 下次从新 offset 拉
```

两次跳转：**逻辑序号 → 物理字节**，全程 O(1)。

---

## 五、可靠性两道闸（和普通消息强相关）

### 刷盘（内存 → 磁盘）

```
ASYNC_FLUSH：写 PageCache 就返回成功，后台线程刷盘 → 快，但断电丢最近一批
SYNC_FLUSH ：强制 fsync 落盘才返回     → 慢，但一条不丢
```

### 主从复制

```
ASYNC_MASTER：Master 写完就返回，异步同步 Slave → 快，Master 挂可能丢
SYNC_MASTER ：Slave 也写完才返回        → 稳，吞吐降
```

> 丢不丢的经典三连：同步发送 + SYNC_FLUSH + SYNC_MASTER = 全链路不丢，代价是吞吐。

---

## 六、失败重试与死信

```
消费失败 → 返回 RECONSUME_LATER
  → 消息转投 %RETRY%消费者组 的 Topic（不是原队列）
  → 按延迟等级 10s/30s/1m/2m... 逐级重试，最多 16 次
  → 还失败 → 进 %DLQ%消费者组（死信队列），人工介入
```

**注意**：重试转投到别的 Topic，所以普通消息**不保证顺序**。

---

## 七、面试话术（30 秒）

> 普通消息是 RocketMQ 的底座。发消息：Producer 查 NameServer 拿路由，选一个 Queue 经 Netty 发到 Broker；Broker 把正文顺序追加进 CommitLog——所有 Topic 混写一个文件为了磁盘顺序写，后台线程再往该 Topic 的 ConsumeQueue 补 20 字节定长索引；消费者 pull 时按消费位点 ×20 字节定位索引、再去 CommitLog 读正文。消费后消息不删，位点推进，所以可回溯。可靠性两道闸：同步刷盘保证不丢、主从同步保证高可用。消费失败转投 %RETRY% 重试 16 次，最后进 %DLQ%。

---

## 八、完整代码案例

```xml
<!-- Maven 依赖 -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>4.9.4</version>
</dependency>
```

### 8.1 Producer

```java
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

public class OrdinaryProducer {
    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("demo-producer-group");
        producer.setNamesrvAddr("localhost:9876");
        producer.start();

        try {
            // 同步发送：等 Broker 确认才返回
            Message msg = new Message("demo-topic", "tagA", "订单消息".getBytes());
            SendResult result = producer.send(msg);
            System.out.println("发送成功: msgId=" + result.getMsgId()
                    + ", queueId=" + result.getMessageQueue().getQueueId()
                    + ", offset=" + result.getQueueOffset());
        } finally {
            producer.shutdown();
        }
    }
}
```

### 8.2 Consumer

```java
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

public class OrdinaryConsumer {
    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("demo-consumer-group");
        consumer.setNamesrvAddr("localhost:9876");
        consumer.subscribe("demo-topic", "*");   // 订阅所有 tag

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                System.out.printf("收到消息: %s, queueId=%d, offset=%d%n",
                        new String(msg.getBody()),
                        msg.getQueueId(),
                        msg.getQueueOffset());
            }
            // 返回成功才推进消费位点；失败返回 RECONSUME_LATER 会重试
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        System.out.println("消费者已启动");
    }
}
```
