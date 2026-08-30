# RabbitMQ 交换机类型与路由原理

> direct / fanout / topic / headers 四种 Exchange + 路由底层原理

---

## 一、先分清两个概念（面试常混）

```
Exchange（交换机）：消息先进交换机，再由交换机按规则路由到队列
Queue（队列）    ：真正存消息、被消费者消费的地方
Binding（绑定）  ：交换机到队列的"连线"，带着 binding key
RoutingKey       ：消息携带的路由关键字，交换机据此匹配
```

**一句话：Producer 从不直接把消息发到队列，而是发给 Exchange，由 Exchange 决定投给哪些队列。**

---

## 二、四种 Exchange 类型

### 1. Direct（直连，默认）

```
路由规则：RoutingKey 完全等于 BindingKey 才投递

                    ┌─ QueueA (binding key = "error")
Exchange(direct) ───┤
                    └─ QueueB (binding key = "info")

消息 RoutingKey="error" → 只进 QueueA
消息 RoutingKey="info"  → 只进 QueueB
消息 RoutingKey="warn"  → 谁都不进，丢弃
```

场景：日志分级、按任务类型分发。

### 2. Fanout（广播）

```
路由规则：忽略 RoutingKey，消息广播给所有绑定队列

Exchange(fanout) ──┬─ QueueA
                   ├─ QueueB
                   └─ QueueC     ← 一条消息三队各一份
```

场景：配置变更通知、全网广播、发布/订阅。

### 3. Topic（主题，通配符）

```
路由规则：RoutingKey 按"."分词，BindingKey 支持通配符
  * 匹配一个词
  # 匹配零个或多个词

BindingKey="order.*"     → 匹配 order.create、order.pay（一个词）
BindingKey="order.#"     → 匹配 order.create、order.pay.success（任意）
BindingKey="*.error.#"   → 匹配 a.error.xxx

RoutingKey="order.pay"   → 命中 "order.*" 和 "order.#" 两个队列
```

场景：多维度分类，灵活路由——订单按"业务.事件.状态"三层主题分发。

### 4. Headers（头匹配，几乎不用）

```
路由规则：不看 RoutingKey，看消息头（Headers）里的键值对
  x-match = all → 头里所有键值都匹配才投
  x-match = any → 任一匹配即投

性能差（每条消息解析 header），实际生产很少用，了解即可
```

---

## 三、路由底层原理（进阶）

### 路由表怎么存

```
Exchange 内部维护一张路由表（binding table）：
  direct  → HashMap<RoutingKey, List<Queue>>
  fanout  → List<Queue>（所有绑定队列）
  topic   → Trie 树（按 "." 分词建前缀树，支持 * # 通配符匹配）
```

### 匹配效率差异

| 类型 | 数据结构 | 匹配复杂度 |
|------|---------|-----------|
| direct | HashMap | O(1) |
| fanout | List | O(1) 全投 |
| topic | Trie 树 | O(词数) |
| headers | 逐条比较 header | 最慢 |

### 消息投递流程（一条消息的旅程）

```
① Producer 连接 → 发消息(Exchange + RoutingKey + body)
② Channel 把消息交给 Exchange
③ Exchange 查路由表 → 得到匹配的 Queue 列表
④ 对每个 Queue：把消息副本写入队列
   ├─ 持久化消息 → 写磁盘 + 入内存索引
   └─ 非持久化 → 只进内存
⑤ 有消费者 → 按 push（真推）投递；无消费者 → 堆积在队列
```

---

## 四、易错点（面试扣分点）

```
① "消息发给队列" ✗ → 正确：发给 Exchange，Exchange 路由到队列
② fanout 忽略 RoutingKey ✓，direct 完全相等 ✓，topic 通配符 ✓
③ 消息不匹配任何队列 → 直接丢弃（除非配了 mandatory + 返回机制）
④ 一个 Exchange 可以绑定多个队列；一个队列也可被多个 Exchange 绑定
```

---

## 五、面试话术（30 秒）

> RabbitMQ 四种交换机：direct 按 RoutingKey 完全匹配，fanout 广播给所有绑定队列，topic 按点分词的 * 和 # 通配符匹配，headers 看消息头几乎不用。底层原理：Producer 把消息发给 Exchange 而非队列，Exchange 维护一张路由表——direct 用 HashMap O(1) 匹配，fanout 直接全投，topic 用 Trie 前缀树支持通配符；消息按路由结果复制到各匹配队列。记忆锚点：direct 精确、fanout 广播、topic 模式、headers 冷门。
