# Redis 大 Key 问题与处理

> 面试高频：大 Key 会引发哪些生产问题？怎么发现？怎么处理？

---

## 一、大 Key 的界定

| 类型 | 大 Key 标准 |
|------|------|
| **String** | value > 10KB 算大，> 10MB 算特大 |
| **List / Set / ZSet / Hash** | 元素 > 5000 计大，> 50000 计算特大 |

---

## 二、五大危害：渐进式推演

> 从 Redis 的核心特征——**单线程**出发，一步步推出大 Key 带来的连锁反应。

### 第一层：为什么一个 Key 能拖死整个 Redis？

Redis 执行命令是单线程的。无论你连接多少客户端，**同一时刻只有一条命令在跑**。

```
客户端 A：GET user:123       —— 0.01ms   ✅
客户端 B：GET user:456       —— 0.01ms   ✅
客户端 C：DEL big_set        —— 200ms    🔴 停在这！
客户端 D：GET user:789       —— 排队等着   🔴
客户端 E：GET user:999       —— 排队等着   🔴
...
```

DEL 一个上百万元素的集合，Redis 主线程要逐一释放所有元素的内存。这 200ms 内，**所有请求都在排队**。

> **根因：单线程 → 一个慢操作堵死整条路。**

---

### 第二层：阻塞主线程 → 雪崩

```
一个客户端 DEL big_set
         ↓
  主线程阻塞 200ms+
         ↓
  所有客户端请求排队等待
         ↓
  等待超时 → 客户端认为 Redis 挂了 → 抛异常 → 重试
         ↓
  重试请求堆积 → 连接池耗尽
         ↓
  上游服务超时 → 调用链崩塌 → 雪崩
```

不只是 DEL。`SMEMBERS`、`HGETALL`、`LRANGE 0 -1` 这些"全量拿"操作一样会触发。

> **这层悟道：一个 DEL 的代价不是 200ms，而是 200ms × 所有正在等待的客户端数。**

---

### 第三层：集群模式下 → 内存不均

Cluster 模式用 16384 个哈希槽分布数据。一个 Key 落在一个槽上，一个槽落在一个节点上。

```
集群 3 节点，各 16GB 内存：

  Node1: [hash_slot_0 ~ 5460]  → 某个大 Hash 占了 14GB → 快爆了 🔴
  Node2: [hash_slot_5461 ~ 10922] → 正常，用了 4GB    ✅
  Node3: [hash_slot_10923 ~ 16383] → 正常，用了 3GB   ✅

Node1 快 OOM → 加内存 → 还是这个节点吃 → 加机器也没用！
```

**Redis Cluster 按 key 分片，不按 key 内部元素分片。** 一个大 Key 的所有元素都在同一个节点，无法水平打散。

> **这层悟道：集群能解决"数据多"的问题，但解决不了"一个 key 太大"的问题。大 Key 是集群的盲区。**

---

### 第四层：网络带宽打满

不是只有"修改"才占带宽，"读取"同样占。

```
一个 String value = 10MB
并发 100 个请求同时 GET → 10MB × 100 = 1GB 网络出流量

Redis 服务器：上行带宽（一般是 1Gbps 或 10Gbps）
  → 1GB = 8Gbps → 秒级打满网卡
  → 其他请求的响应被挤占 → 丢包 → 超时
```

同样，大集合的 `SMEMBERS` 返回几十万条数据一样打满带宽。

> **这层悟道：Redis 快在内存计算，但数据终究要走网络。大 Key 让网络成为瓶颈。**

---

### 第五层：主从同步卡顿

Redis 主从同步分两种：

| 同步方式 | 触发场景 | 携带数据 |
|------|------|------|
| **全量同步** | 从节点首次连接 / 断线太久 | RDB 文件 整个发过去 |
| **增量同步** | 短暂断线重连 | 复制缓冲区中的增量命令 |

大 Key 对两种同步都有影响：

```
全量同步：
  RDB 文件包含大 Key → 体积巨大 → 传输出错概率高 → 重传 → 同步迟迟完不成

增量同步：
  大 Key 的修改产生大条命令 → 塞满复制缓冲区（repl-backlog-size）
  → 环形缓冲区写满 → 覆盖掉未同步的旧命令
  → 从节点发现自己的 offset 被覆盖了 → 只能重新全量同步
  → 全量同步又有大 Key → 死循环
```

> **这层悟道：大 Key 让主从同步从"增量"退化成"全量"，从节点一直追不上主节点，读请求打到旧数据上。**

---

### 第六层：慢查询

不是只有 DEL 和全量读才慢，任何操作大 Key 的命令都可能进慢查询日志：

```sql
-- 慢操作示例
SMEMBERS big_set          -- 返回 50万个元素 → 数百 ms
HGETALL big_hash          -- 返回 10万个字段 → 数百 ms  
LRANGE big_list 0 -1      -- 返回全量 → 数百 ms
ZRANGE big_zset 0 -1      -- 返回全量 → 数百 ms
SINTER big_set1 big_set2  -- 两个大集合求交集 → 更慢
```

Redis 默认 `slowlog-log-slower-than 10000`（10ms），大 Key 操作轻松触发。慢查询日志堆积 → 运维告警 → 排查才发现是大 Key。

> **这层悟道：慢查询是大 Key 的"报警器"。线上看到慢查询频繁且都是 `SMEMBERS` / `HGETALL`，第一时间检查是否有大 Key。**

---

### 六层推演串联图

```
Redis 单线程
    │
    ├── DEL / 全量读大 Key → 主线程阻塞（第1-2层）
    │       └── 所有请求排队 → 超时 → 雪崩
    │
    ├── 集群 HotKey 无法打散 → 单节点内存爆炸（第3层）
    │
    ├── 大 Value 并发读取 → 网络带宽打满（第4层）
    │
    ├── RDB 大副本 / repl-backlog 溢出 → 主从同步卡顿（第5层）
    │
    └── SMEMBERS / HGETALL / 集合运算 → 慢查询（第6层）
```

### 2.1 阻塞 Redis 主线程（最致命）

Redis 单线程执行。大 Key 删除（`DEL`）一个上百万元素的集合，CPU 耗时可能几十毫秒甚至秒级，**整个 Redis 在这段时间内不响应任何请求**。

```
DEL big_set → 阻塞 200ms+ → 所有客户端超时 → 连接池耗尽 → 雪崩
```

### 2.2 集群内存不均

Cluster 模式下，数据按槽散列到不同节点。一个大 Key 在单个节点 → 该节点内存爆炸扩容 → 其他节点空着 → 无法水平扩展。

### 2.3 网络带宽打满

大 Value 10MB，并发 100 次请求 → 1GB 流量 → 网卡满载 → 其他请求不可用。

### 2.4 主从同步卡顿

全量同步或增量同步携带大 Key → 复制缓冲区溢出 → 主从延迟巨大 → 从节点不可用。

### 2.5 慢查询

```sql
SMEMBERS big_set       -- 全量返回
HGETALL big_hash       -- 全量返回
LRANGE big_list 0 -1   -- 全量返回
```

以上操作在元素量大时会触发慢查询，CPU 尖刺。

---

## 三、如何发现大 Key

### 3.1 redis-cli --bigkeys

```bash
redis-cli --bigkeys
# 遍历整个实例，输出每个数据类型中最大的 key
# 优点：内置，无需额外安装
# 注意：生产环境慎用，会扫描所有 key（耗时 + 影响性能）
```

### 3.2 离线分析 RDB 文件

```bash
pip install rdbtools
rdb -c memory dump.rdb > memory.csv
# 分析哪些 key 占用内存最多
```

### 3.3 MEMORY USAGE 命令

```bash
MEMORY USAGE <key>
# 返回 key 实际占用字节数（Redis 4.0+）
```

### 3.4 监控平台

哨兵 / 集群模式下，通过 `INFO` 命令监控每个节点内存大小、带宽使用及慢查询日志。

### 3.5 scan 脚本批量扫描

```bash
SCAN 0 MATCH * COUNT 1000
# 分批遍历 key，对每个 key 用 DEBUG OBJECT 或 MEMORY USAGE 获取大小
```

---

## 四、如何处理大 Key

### 4.1 防：源头避免与优化方案

#### 4.1.1 String（大 Value）

| 方案 | 做法 | 示例 |
|------|------|------|
| **压缩** | 存前 GZip/Snappy/LZ4 压缩。10KB → 2KB | `set key compress(value)`，读时解压 |
| **只存索引** | Redis 只存 ID/URL，真实数据放 OSS/MinIO/HBase | `set article:123 "{title,url}"` → 正文走 OSS |
| **分段存储** | 特大 String 拆成多段 | `set article:123:p1 part1`、`set article:123:p2 part2`，读完拼接 |

#### 4.1.2 Hash（大对象 / 大字典）

| 方案 | 做法 | 示例 |
|------|------|------|
| **按业务维度拆** | 原来的大 Hash 按子模块拆成多个小 Hash | `user:10086` → `user:basic:10086` + `user:extra:10086` + `user:settings:10086` |
| **按 hash 分桶** | 用 `CRC32(key) % N` 把同一逻辑对象散列到 N 个小 Hash | `user:10086` → `user:0:10086`、`user:1:10086`... `user:N-1:10086`<br>读写时先算桶号再操作 |

```
原始方案：
  HSET user:10086 name 张三 age 25 email xxx@xx.com address 北京...20个字段
  → 一次性 HGETALL 很重

拆分后：
  user:basic:10086    → name, age, gender
  user:contact:10086  → email, phone, address
  user:settings:10086 → lang, theme, notification
  → 各维度独立存取，不需要每次都全量
```

#### 4.1.3 List（消息队列 / 时间线）

| 方案 | 做法 |
|------|------|
| **按时间分片** | `timeline:2024-01`、`timeline:2024-02`... 每月一个 List |
| **按 hash 路由** | `queue:0`、`queue:1`... `queue:9`。生产者 `abs(hash(id) % 10)` 入队，消费者轮询 |
| **消费即删** | 用 `LPOP`/`RPOP` 消费完即刻减少元素数，不要只读不删 |

#### 4.1.4 Set（标签 / 关注列表）

| 方案 | 做法 |
|------|------|
| **hash_tag 分桶** | `user:follows:0` 到 `user:follows:99`，`SADD user:follows:{hash(id)%100} id` |
| **只存热数据** | 最近活跃的好友存 Redis，冷数据落到 MySQL，用 LRU 淘汰 |
| **Bloom Filter 替代** | 只需判"是否存在"的场景（如去重），用布隆过滤器代替 Set |

#### 4.1.5 ZSet（排行榜 / 延时队列）

| 方案 | 做法 |
|------|------|
| **按天/月拆分** | `rank:daily:2024-01-15`、`rank:total`，热点数据单独 key |
| **Top-N 截断** | 排行榜只保留前 1000 名，`ZREMRANGEBYRANK key 0 -1001` 定时清理 |
| **分离冷热** | 近 7 天数据放 Redis，历史数据放离线数仓 |

#### 4.1.6 通用兜底方案

| 方案 | 说明 |
|------|------|
| **业务上限** | 每个 key 最多存 N 条，超了拒绝或异步落到 DB |
| **TTL 兜底** | 给大 Key 设过期时间，防止"只增不减"永久堆积 |
| **双写策略** | 写 Redis 同时异步写 DB。Redis 只保留最新/最热的，查不到回源 DB |

### 4.2 删：安全删除（不阻塞）

```
不要直接 DEL！

Redis 4.0+：
  UNLINK <key>    -- 异步删除，主线程不阻塞

Redis 4.0 以下或集合大 Key：
  用 SCAN 系列命令分批小次删除：
    SSCAN big_set 0 COUNT 100        -- 每次只取 100 个元素
    SREM big_set 取出的元素            -- 少量删除，循环
    最终 DELETE big_set               -- 空集合直接删

  对 Hash：HSCAN + HDEL 同理
  对 List ：LTRIM + RPOP / LPOP 分批删
  对 ZSet：ZSCAN + ZREM 同理
```

### 4.3 配置：延迟释放

```bash
# Redis 5.0+ 推荐开启以下 lazyfree 配置
lazyfree-lazy-user-del yes     # DEL → UNLINK 自动异步处理
lazyfree-lazy-expire yes       # 过期 key 异步删除
lazyfree-lazy-eviction yes     # 淘汰 key 异步删除
lazyfree-lazy-server-del yes   # 内部操作用 UNLINK
```

---

## 五、一句话总结

```
大 Key 最大危害：阻塞主线程 → 全量超时 → "雪崩"。
发现：--bigkeys / RDB 离线分析 / MEMORY USAGE。
防：压缩 + 拆分 + 只存 ID。
删：UNLINK → 不可用时：分批 SCAN + 小次 SREM → 最终删空集合。
```
