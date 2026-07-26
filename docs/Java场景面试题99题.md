# Java 场景面试题 99 题（含答案）

---

## 一、MySQL / 数据库

### 1. 外卖平台每天有 1000 万笔订单查询该如何优化？

**分库分表**：按用户 ID 或订单 ID 哈希取模，将订单表拆分到多个库和多张表。

**读写分离**：主库写入，从库读取，一主多从架构。

**索引优化**：
- 对高频查询字段建联合索引（如用户 ID + 下单时间）
- 覆盖索引避免回表

**冷热分离**：近期订单存 MySQL（热数据），历史订单归档到 ES/HBase/TiDB。

**缓存**：热点用户订单缓存到 Redis，设置合理过期时间。

**ES 异构**：订单数据同步到 Elasticsearch，复杂搜索走 ES。

**归档策略**：3 个月前的订单迁移到归档库/对象存储。

---

### 3. 每天导入 100 万数据导致数据库死锁

**根本原因**：批量 INSERT 时多个事务竞争同一间隙锁/行锁。

**解决方案**：
- **分批提交**：每 1000 条提交一次事务，缩小锁持有时间
- **按主键排序**：导入前按主键排序，保证每个事务访问的锁范围不重叠
- **降低隔离级别**：RC（读已提交）代替 RR（可重复读），减少间隙锁
- **使用 INSERT INTO ... VALUES 批量语法**，而不是逐条插入
- **关闭自动提交**，手动控制事务边界
- **使用 LOAD DATA INFILE** 代替 INSERT 语句
- 在业务低峰期执行导入

---

### 5. MySQL 的 WHERE 问题

WHERE 条件的执行顺序和优化：

1. **执行顺序**：FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT
2. **索引失效场景**，在 WHERE 中：
   - `WHERE age + 1 = 20`，对列进行运算
   - `WHERE name LIKE '%abc'`，前模糊查询
   - `WHERE phone = 13800000000` 但 phone 是 VARCHAR，发生隐式类型转换
   - `WHERE a = 1 OR b = 2`，OR 两边字段不同
   - `WHERE status != 1`，不等于、NOT IN、NOT EXISTS
3. **优化器会重排序**：MySQL 优化器不一定按写的顺序执行 WHERE 条件

---

### 27. SQL 的执行过程

```
客户端 → 连接器 → 查询缓存(8.0废弃) → 分析器 → 优化器 → 执行器 → 存储引擎
```

1. **连接器**：验证用户、权限
2. **分析器**：词法分析、语法分析，生成 AST
3. **优化器**：选择索引、决定 JOIN 顺序，生成执行计划
4. **执行器**：调用存储引擎 API 读取数据
5. **存储引擎（InnoDB）**：Buffer Pool → 磁盘

---

### 28. 单表最多数据量需要分表

**没有绝对值，取决于**：

- **行大小**：单行 1KB，2000 万行约 20GB
- **查询性能**：B+ 树高度 ≤ 3 为佳（约千万级）
- **经验值**：单表 500 万 ~ 2000 万行建议分表

**判断标准**：
- 查询 P99 耗时 > 200ms
- B+ 树层高 ≥ 4
- DDL 操作耗时不可接受
- 单库磁盘 > 2TB

---

### 29. B 树和 B+ 树的区别

| 对比维 | B 树 | B+ 树 |
|--------|------|-------|
| 数据存储 | 非叶子节点也存 data | 仅叶子节点存 data |
| 叶子节点 | 独立，无连接 | 双向链表连接 |
| 范围查询 | 中序遍历，效率低 | 链表遍历，效率高 |
| 查询稳定性 | 可能在非叶子节点命中 | 必须到叶子节点 |
| IO 次数 | 不稳定 | 稳定 |

**MySQL 选择 B+ 树原因**：范围查询高效、IO 次数稳定、更适合磁盘预读。

---

### 30. MySQL 引擎层 Buffer Pool 工作过程原理

Buffer Pool 是 InnoDB 的内存缓存区，缓存数据页和索引页。

**核心结构**：
- **Free List**：空闲页链表
- **LRU List**：缓存页链表（变种 LRU，Young 区 5/8 + Old 区 3/8）
- **Flush List**：脏页链表

**工作流程**：
1. 读取数据页 → 先查 Buffer Pool
2. 未命中 → 从磁盘加载到 Free List 页 → 放入 LRU Old 区头部
3. 再次访问 → 移到 Young 区头部
4. 修改数据 → 页变脏 → 加入 Flush List
5. 后台线程定期刷脏页到磁盘（Checkpoint）

**预读机制**：线性预读、随机预读。

---

### 31. MySQL 怎么做到 Redo Log 崩溃恢复的？

**WAL（Write-Ahead Logging）**：先写日志再写磁盘。

**Redo Log**：物理日志，记录"对哪个数据页做了什么修改"。

**崩溃恢复流程**：
1. 重启后检查 Redo Log 的 Checkpoint LSN
2. 从 Checkpoint 开始，应用所有 Redo Log 到数据页（前滚）
3. 回滚未提交事务（借助 Undo Log）
恢复到崩溃前的一致性状态

**关键机制**：Redo Log 循环写，Checkpoint 推进，保证数据不丢失（`innodb_flush_log_at_trx_commit=1`）。

---

### 32. Binlog 刷盘机制

Binlog 是 Server 层的逻辑日志，记录所有修改操作。

**刷盘策略（sync_binlog）**：
- `sync_binlog=0`：每次提交只写 OS 缓存，不 fsync，性能最好但可能丢失
- `sync_binlog=1`：每次提交都 fsync 到磁盘，最安全
- `sync_binlog=N`：每 N 次提交 fsync 一次

**写入流程**：
1. 事务执行过程中写 binlog cache
2. commit 时写入 page cache（write）
3. 根据 sync_binlog 决定是否 fsync

---

### 33. Binlog 和 Redo Log 缺一不可？

**是的，缺一不可**。

| 对比 | Redo Log | Binlog |
|------|----------|--------|
| 层级 | InnoDB 引擎层 | Server 层 |
| 记录内容 | 物理日志（页修改） | 逻辑日志（SQL） |
| 写入方式 | 循环写 | 追加写 |
| 用途 | 崩溃恢复 | 主从复制、数据恢复 |

**两阶段提交**保证一致性：
1. Prepare 阶段：写 Redo Log（prepare 状态）
2. Commit 阶段：写 Binlog
3. 写 Redo Log（commit 状态）

缺少任何一个：崩溃恢复时数据不一致，或主从数据不一致。

---

### 34. 聚集索引和非聚集索引

| 对比 | 聚集索引 | 非聚集索引（二级索引） |
|------|----------|------------------------|
| 叶子节点 | 存整行数据 | 存主键值 |
| 数量 | 每表仅一个 | 可有多个 |
| 查询 | 直接拿到数据 | 需要回表（查聚集索引） |
| 物理存储 | 数据按索引顺序存放 | 索引和数据分开存放 |

**覆盖索引**：查询列都在索引中，不需要回表。

---

### 35. count(\*)、count(1)、count(字段) 谁更快？有什么区别

**性能排序（5.7+ 无 WHERE 条件下）**：`count(*) ≈ count(1) > count(字段)`

| 写法 | 含义 | 性能 |
|------|------|------|
| count(*) | 统计所有行（含 NULL） | 最快，优化器专门优化 |
| count(1) | 统计所有行（含 NULL） | 与 count(*) 等价 |
| count(字段) | 统计该字段非 NULL 的行 | 慢，需判断 NULL，不走覆盖索引优化 |
| count(主键) | 统计主键非 NULL 的行 | 慢于 count(*)，需解析主键 |

**原因**：InnoDB 中 `count(*)` 会选择最小的二级索引遍历，而 `count(字段)` 还要判断 NULL。

---

### 36. 前模糊索引失效

**原因**：B+ 树索引按字段值排序，前模糊（`LIKE '%abc'` 或 `LIKE '%abc%'`）无法确定扫描范围。

**`LIKE 'abc%'`** 走索引（范围扫描），因为可以用"abc"定位起点。

**解决方案**：
- 使用 ES 全文检索
- 反转字段存储，`LIKE '%abc'` → 反转字段 `LIKE 'cba%'`
- 使用 MySQL 全文索引（`FULLTEXT`）

---

### 37. 分库分表 ID 冲突解决方案

1. **雪花算法（Snowflake）**：64 bit，时间戳 + 机器 ID + 序列号，趋势递增
2. **号段模式（Leaf-Segment）**：每次从 DB 取一个号段放到内存，用完再取
3. **Redis 自增**：单线程自增，性能高但依赖 Redis 高可用
4. **UUID**：无冲突但无序，插入性能差（页分裂）
5. **美团的 Leaf**：支持号段模式和 Snowflake 模式
6. **百度 UidGenerator**：基于 Snowflake 改良

**推荐**：雪花算法（高性能、趋势递增）。

---

### 38. 深分页为什么慢，怎么优化？

**原因**：`LIMIT 100000, 20` 需要扫描前 100000+20 行再丢弃前 100000 行。

**优化方案**：

1. **延迟关联**：
```sql
SELECT * FROM orders o
INNER JOIN (SELECT id FROM orders ORDER BY id LIMIT 100000, 20) tmp
ON o.id = tmp.id;
```

2. **游标分页**：记住上次查询的最后一条 ID
```sql
SELECT * FROM orders WHERE id >  id LIMIT 20;
```

3. **ES 分页**：复杂搜索走 ES，ES 的 `search_after` 支持深分页

4. **禁止跳页**：只提供上一页/下一页

---

### 39. MySQL 的隔离级别实现原理 MVCC

**四种隔离级别**：
- **读未提交（RU）**：直接读最新数据，无视图
- **读已提交（RC）**：每次 SELECT 生成新 ReadView
- **可重复读（RR）**：事务开始时生成一个 ReadView
- **串行化**：加锁

**MVCC 实现**：
- 每行有隐藏列：`trx_id`（事务 ID）、`roll_pointer`（回滚指针）
- **ReadView** 包含活跃事务列表，判断可见性：
  - `trx_id` < min_trx_id → 可见
  - `trx_id` > max_trx_id → 不可见，沿 roll_pointer 找 Undo Log
  - 在活跃列表中 → 不可见

**RR 解决幻读**：间隙锁（Next-Key Lock）+ MVCC。

---

### 58. 阿里开发手册为什么禁止超过三张表 JOIN

1. **笛卡尔积爆炸**：3 张表 JOIN 中间结果可能非常巨大
2. **优化器选择困难**：JOIN 越多，优化器选错执行计划的概率越大
3. **可维护性差**：SQL 复杂，调试困难
4. **性能瓶颈**：大量临时数据写入磁盘，IO 成为瓶颈
5. **数据库资源占用**：长时间占用连接、CPU、内存

**替代方案**：
- 分步查询，在应用层组装
- 冗余字段减少 JOIN
- 使用 ES 等异构数据源

---

### 59 / 76. MySQL 中 LIKE 模糊查询如何优化

1. **前缀匹配走索引**：`LIKE 'abc%'`
2. **全文索引（FULLTEXT）**：适合中文分词场景
3. **Elasticsearch**：复杂模糊查询、中文分词
4. **反转字段**：后模糊变前模糊
5. **使用 `INSTR()` / `LOCATE()`** 在某些场景下比 LIKE 快
6. **缩小数据范围**：先精确条件过滤，再 LIKE

---

### 60 / 77. count(1)、count(\*) 与 count(列名) 的区别

同第 35 题。**总结**：`count(*)` 和 `count(1)` 等效最快，`count(列名)` 排除 NULL 且更慢。无特殊需求用 `count(*)`。

---

### 61. 什么时候索引失效反而提升效率

**索引失效（全表扫描）反而更快的场景**：

1. **数据量小**：几万行以内，全表扫描比索引回表更快
2. **返回数据量大（低选择性）**：如查询 80% 的数据，回表开销大于全表扫描
3. **区分度极低的字段**：如性别字段，索引无法有效过滤
4. **频繁更新的表**：索引维护开销大于查询收益
5. **写多读少**：索引拖慢写入

**优化器判断**：当估算的全表扫描代价 < 索引扫描代价，自动放弃索引。

---

### 62. MySQL 的深度分页如何优化

同第 38 题。补充：

- **业务限制**：限制最大翻页（如淘宝仅前 100 页）
- **异步导出**：大数据量分页改为异步导出文件
- **ClickHouse/Doris**：OLAP 场景用列存引擎

---

### 63 / 81. 为什么不建议使用存储过程

1. **可移植性差**：不同数据库语法不同，绑死厂商
2. **难以版本控制**：存储过程不在 Git 中，难以 Code Review
3. **调试困难**：没有像 Java 的 IDE 断点调试
4. **性能问题**：存储过程编译一次，但无法利用新版本优化器
5. **扩展性差**：数据库成了清晰**：业务逻辑应放在应用层

**原则**：数据库只做存储和简单查询，复杂逻辑放应用层。

---

### 64 / 83. 区分度不高的字段建索引一定没用吗

**不一定没用，看场景**：

1. **联合索引**：区分度低的字段在联合索引前面仍然有效（如 `CREATE INDEX idx ON (status, create_time)`）
2. **覆盖索引**：即使区分度低，如果查询列都在索引中可避免回表
3. **少量数据的快速查找**：如果用 `LIMIT`，即使区分度低也能快速返回
4. **数据倾斜场景**：如 99% 是 0，1% 是 1，查 1 时索引非常有效

---

### 66. 分库分表后如何进行跨库 JOIN

**原则**：尽量避免跨库 JOIN。

**替代方案**：

1. **字段冗余**：把 JOIN 需要的字段冗余到目标表
2. **应用层 JOIN**：分别查两张表，在代码里组装
3. **全局表**：小表每个库都存一份
4. **数据异构**：同步到 ES/TiDB 等支持 JOIN 的数据源
5. **ER 分片**：相关联的表用同一个分片键，使相关数据落在同一库

---

### 67. 分库分表如何预估分多少个库和多少张表

**估算公式**：
```
分表数 = 未来3年数据总量 / 单表合理容量
分库数 = 分表数 / 单库可承载表数
```

**关键因素**：
- **数据增长预估**：日增量 × 365 × 3
- **单表容量**：1000 万 ~ 2000 万行为宜
- **单库容量**：磁盘 < 2TB，连接数能支撑
- **QPS 预估**：单库承载 QPS 约 1000 ~ 2000

**经验**：
- 建议 2 的幂次方（方便扩容）
- 分表数 > 分库数，方便后续迁移

---

### 78. SQL 用了函数一定会索引失效吗

**不一定**。

**会失效**：`WHERE DATE(create_time) = '2024-01-01'`
- 函数作用在索引列上 → 索引失效

**不会失效**：`WHERE create_time = DATE('2024-01-01')`
- 函数作用在常量上 → 索引不受影响

**MySQL 8.0 改进**：
- 函数索引（Functional Index）：`CREATE INDEX idx ON orders((DATE(create_time)))`

---

### 79. TRUNCATE、DELETE、DROP 的区别

| 对比 | DELETE | TRUNCATE | DROP |
|------|--------|----------|------|
| 类型 | DML | DDL | DDL |
| 删除内容 | 行数据 | 全表数据 | 表结构 + 数据 |
| 回滚 | 可回滚 | 不可回滚 | 不可回滚 |
| 触发器 | 触发 | 不触发 | 不触发 |
| 速度 | 慢（逐行删） | 快（整页删） | 极快 |
| 自增 ID | 不重置 | 重置 | — |
| 空间释放 | 不释放（碎片） | 释放 | 释放 |

---

### 80. MySQL 8 的索引跳跃扫描（Index Skip Scan）是什么

**作用**：当联合索引前缀列区分度低时，优化器会"跳过"前缀列做多次扫描。

**示例**：
```sql
CREATE INDEX idx ON t(gender, age);
SELECT * FROM t WHERE age = 25;  -- gender 未在 WHERE 中
```
**传统**：索引失效。**MySQL 8.0**：自动拆成 `gender='男' AND age=25` 和 `gender='女' AND age=25` 两次扫描。

**条件**：前缀列区分度低、后列区分度高。

---

### 82. 为什么大厂不建议使用多表 JOIN

同第 58 题。补充：
- 单库瓶颈时无法水平拆分
- 微服务化后数据分散在不同服务，分步查询是常态

---

### 84. 为什么不推荐使用外键

1. **性能问题**：每次 INSERT/UPDATE 都检查外键，加锁
2. **并发能力下降**：外键检查引入额外锁
3. **分库分表不友好**：无法跨库建立外键
4. **维护复杂**：数据修复、归档、迁移时外键是阻碍
5. **级联风险**：级联删除可能导致大范围数据丢失

**替代**：应用层保证数据一致性，定期巡检异常数据。

---

## 二、Redis / 缓存

### 4. Redis 缓存与数据库一致性问题该如何解决？

**经典方案：Cache Aside Pattern**

**读**：先读缓存，命中返回；未命中读 DB，写缓存，返回。

**写**：
1. 更新数据库
2. 删除缓存（不是更新缓存）

**为什么删缓存不是更新缓存**：更新操作在并发下可能产生脏数据。

**延迟双删**：
1. 删除缓存
2. 更新数据库
3. 延迟（如 1s）再删除缓存

**最终一致性方案**：
- **Canal + MQ**：监听 binlog → 异步更新/删除缓存
- 缓存设置过期时间兜底

---

### 18. 使用 Redis 出现缓存击穿/雪崩/穿透怎么解决

**缓存穿透**（查不存在的数据）：
- 布隆过滤器（Bloom Filter）拦截
- 缓存空值，TTL 短

**缓存击穿**（热点 Key 过期瞬间高并发打 DB）：
- 互斥锁（`SETNX`）只让一个请求查 DB
- 逻辑过期（永不过期 + 异步更新）

**缓存雪崩**（大量 Key 同时过期或 Redis 宕机）：
- TTL 加随机值打散
- Redis 高可用（主从/哨兵/集群）
- 限流 + 降级
- 多级缓存（本地缓存 + Redis）

---

### 19. 如何使用 Redis 记录上亿用户连续登录天数

**使用 Bitmap**：

```
Key: sign:user:{userId}:{year}
Offset: 第几天（0~365）
Value: 0/1
```

```shell
SETBIT sign:user:1001:2024 100 1   # 第100天签到
BITCOUNT sign:user:1001:2024        # 统计签到天数
```

**连续登录判断**：取整年 Bitmap，从当天往前遍历找连续 1。

**优势**：一个用户一年仅需 46 字节（365bit）。

---

### 20. 给你一亿 Redis Keys，统计双方的共同好友

**方案一：Set 交集**
```
SINTER user:friends:{userA} user:friends:{userB}
```
时间复杂度 O(N*M)，数据量大可能阻塞。

**方案二：Bitmap**
```
Key: user:friends:bitmap:{userId}
offset: friendUserId
值: 1 表示好友

BITOP AND result user:friends:bitmap:{A} user:friends:bitmap:{B}
BITCOUNT result
```
12.5MB/用户（一亿位），适合海量用户。

**方案三**：分桶 + 异步计算，结果缓存。

---

### 21. Redis 如何实现上亿用户实时积分排行榜

**使用 Sorted Set（ZSet）**：

```
ZADD rank:score 1000 user:1001           # 更新积分
ZREVRANK rank:score user:1001            # 排名
ZREVRANGE rank:score 0 99 WITHSCORES     # Top 100
ZREVRANGEBYSCORE rank:score +inf 1000 LIMIT 0 10  # 积分1000附近的排名
```

**优缺点**：
- 适合千万级用户
- 亿级以上，ZSet 内存占用大，考虑分段存储（如按积分区间拆分）

---

### 50. Redis 6 为什么引入了多线程

Redis 6 引入的是 **IO 多线程**，不是命令执行多线程。

**原因**：网络 IO 成为瓶颈。多线程并行处理网络读写，命令执行仍单线程（避免锁开销）。

**配置**：
```
io-threads 4
io-threads-do-reads yes
```

**注意**：多线程仅在 socket 读写时生效，命令处理仍是单线程，仍保证原子性。

---

### 51 / 65. Redis 的热 Key 问题如何解决

**发现热 Key**：
- `redis-cli --hotkeys`
- 客户端统计（如 Jedis 拦截器）
- 监控 QPS 突增的 Key

**解决方案**：
1. **本地缓存**：Caffeine/Guava 再加一层，减少 Redis 请求
2. **Key 拆分**：`hotkey` → `hotkey:1`, `hotkey:2`, ..., `hotkey:N` 分散到不同节点
3. **读写分离**：热 Key 走从节点读
4. **降级**：热 Key 不可用时返回兜底值
5. **提前预热**：活动开始前把热 Key 加载到各节点的本地缓存

---

### 52. Redis 的大 Key 问题如何解决

**定义**：String > 10KB，或集合元素 > 1 万个称大 Key。

**危害**：
- 阻塞 Redis（单线程）
- 网络带宽占用
- 过期时删除导致阻塞
- 迁移困难

**发现**：`redis-cli --bigkeys` 或 `MEMORY USAGE key`

**解决**：
- **拆分**：大 Hash 按 field 拆成多个小 Hash
- **压缩**：大 String 压缩后存储
- **数据结构优化**：用 HyperLogLog 代替 Set 统计 UV
- **定期清理**：设置 TTL，避免无限增长
- **异步删除**：`UNLINK` 代替 `DEL`

---

### 53. 缓存与数据库双写不一致问题如何解决

**核心策略**：先更新数据库，再删除缓存。

**延迟双删**：更新 DB 前删一次，更新 DB 之后延迟（如 1s）再删一次。

**Canal 监听 Binlog**：
1. MySQL 更新 → Canal 捕获 Binlog
2. → 发 MQ → 消费端更新/删除 Redis

**分布式读写锁**：写时对 Key 加锁，读等待，保证读到的不是旧数据。

**最终兜底**：缓存 TTL + 定时任务对账修复。

---

### 54. Redis 中 Key 过期了一定会立即删除吗

**不会立即删除**。三种删除策略：

1. **惰性删除**：访问 Key 时检查是否过期，过期则删除
2. **定期删除**：每 100ms 随机抽一批 Key 检查并删除过期 Key
3. **内存淘汰**：内存不足时触发

**总结**：可能内存中仍有过期 Key 未被删除，直到被访问或定期扫描到。

---

### 55. Redis 的 Key 和 Value 的设计原则有哪些

**Key 设计**：
1. **可读性**：`业务:模块:ID:属性`，如 `order:status:123456`
2. **长度控制**：不宜过长（占内存），也不宜过短（不清晰）
3. **避免特殊字符**：避免空格、换行
4. **统一命名规范**

**Value 设计**：
1. **合理选择数据结构**：计数用 String，排行榜用 ZSet，去重用 Set
2. **控制大小**：Value 不宜过大
3. **设置过期时间**：避免冷数据常驻内存
4. **压缩**：大 Value 考虑压缩

---

### 56. Redis 如何高效安全地遍历所有 Key

**禁止**：`KEYS *`（阻塞 Redis）。

**使用 SCAN 命令**：
```shell
SCAN 0 MATCH order:* COUNT 100
```
- 游标迭代，非阻塞
- 可能返回重复 Key（需业务去重）
- 不能保证完整遍历（遍历期间增删的 Key 可能漏掉或重复）

**安全遍历**：
- 每次 SCAN 返回的游标 > 0 时继续
- 业务层对结果去重

---

### 57. Redis 线上操作最佳实践有哪些

1. **禁用危险命令**：`KEYS`, `FLUSHALL`, `FLUSHDB`, `CONFIG` rename 为空
2. **禁止大 Key**
3. **设置合理的内存上限**：`maxmemory`
4. **配置内存淘汰策略**：`maxmemory-policy`（推荐 `allkeys-lru`）
5. **使用 Pipeline 批量操作**
**：避免频繁建连
7. **密码认证**：`requirepass`
8. **禁止公网暴露**：`bind` 内网地址
9. **慢查询监控**：`slowlog`
10. **RDB/AOF 备份**：持久化保底

---

## 三、JVM

### 85. OOM 一定会导致 JVM 退出吗

**不一定**。

- 如果 OOM 发生在**非主线程**且被 try-catch 捕获，则只有该线程终止，JVM 不退出
- 线程池中任务 OOM 被吞掉，线程池可继续工作
- 但如果 OOM 导致关键线程（如 GC 线程）退出，或整个堆无法分配，JVM 会不可用

**经典案例**：`OutOfMemoryError: unable to create new native thread` → 进程退出。

---

### 86. 对象一定分配在堆中吗

**不一定**。

1. **栈上分配**：逃逸分析后，未逃逸对象可直接在栈上分配，随栈帧销毁
2. **标量替换**：对象可打散为基本类型分配在栈/寄存器
3. **TLAB（Thread Local Allocation Buffer）**：对象在堆中但线程私有区域分配，无锁

**JIT 优化后，很多小对象实际不在堆中分配。**

---

### 87. 内存泄漏和内存溢出的区别

| 对比 | 内存泄漏 | 内存溢出 |
|------|----------|----------|
| 含义 | 对象不再使用但 GC 无法回收 | 内存不够分配新对象 |
| 过程 | 缓慢积累 | 突然爆发 |
| 原因 | 引用未释放、集合持续增长、连接未关闭 | 堆太小、大对象、泄漏累积 |
| 关系 | 泄漏累积 → 最终导致溢出 | 溢出不一定因泄漏 |

---

### 88. JVM 对象分配内存如何保证线程安全

**TLAB（Thread Local Allocation Buffer）**：

1. 每个线程在 Eden 区预分配一块私有缓冲区（TLAB）
2. 对象优先在 TLAB 中分配，无锁
3. TLAB 不够用 → CAS 竞争分配新的 TLAB
4. CAS 失败 → 加锁在 Eden 共享区域分配

**CAS + TLAB 双重机制保证高效线程安全。**

---

### 89. 堆一定是线程共享的吗

**逻辑上是，物理上有例外**：

1. **TLAB**：线程私有分配区，实际在堆中但线程独占
2. **栈上分配**：经逃逸分析，对象可分配在栈上（非堆）
3. **JIT 优化**：标量替换可让对象完全不在堆中

**所以堆逻辑上共享，但物理上存在线程私有的优化区域。**

---

### 90. Class 常量池和运行时常量池的区别

| 对比 | Class 常量池 | 运行时常量池 |
|------|-------------|-------------|
| 位置 | 在 .class 文件中 | 在方法区（元空间） |
| 时间 | 编译期生成 | 类加载后 |
| 内容 | 字面量 + 符号引用 | 字面量 + 符号引用 → 直接引用 |
| 动态性 | 静态 | 可动态添加（如 `String.intern()`） |
| 存储 | 每个类单独 | 每个类单独（共享字符串常量池在堆中） |

---

### 91. 运行时常量池和字符串常量池的区别

- **运行时常量池**：在元空间，每个类一个，存类级别的常量
- **字符串常量池（String Pool）**：在堆中，全局共享，存 String 对象的引用

**关系**：
- Class 文件中的字符串字面量 → 类加载时 → String 对象被创建放入字符串常量池 → 运行时常量池持有对该对象的符号引用（解析为直接引用）

**JDK 7** 之后 String Pool 从永久代移到堆。

---

### 92. 什么情况会导致 JVM 退出

1. **所有非守护线程结束**（正常退出）
2. **`System.exit(n)`** 或 **`Runtime.halt()`**
3. **OOM 且关键线程崩溃**
4. **`SIGTERM / SIGKILL`** 信号
5. **Native（JNI 错误）**
6. **metaspace/堆无法满足关键分配**

---

### 93. JVM 内存为什么要分代

**分代假设**：
- **弱分代假设**：大多数对象朝生夕死
- **强分代假设**：熬过多次 GC 的对象更难回收

**分代优势**：
1. **Minor GC 只扫新生代**，效率高
2. 不同代用不同 GC 算法（新生代复制，老年代标记清除/整理）
3. 减少全堆扫描频率

---

### 94. GC 是任意时候都能进行的吗

**不是**。

- **安全点（Safepoint）**：GC 只能在所有线程到达安全点时进行
  - 方法返回前
  - 循环跳转
  - 异常跳转
- **安全区域（Safe Region）**：线程不在 JVM 执行时（Sleep/Blocked）的区域
- **主动式中断**：GC 设置标志，线程运行到安全点时主动检查并挂起

---

## 四、并发 / 多线程

### 2. QPS 10 万，任务接口耗时 100ms，线程池如何优化？

**线程数计算**：

```
线程数 = QPS × 响应时间 = 100000 × 0.1 = 10000（理论最少）
```

**优化策略**：

1. **IO 密集型**：线程数设大（2~4 倍 CPU 核数 / (1 - IO等待比例)）
2. **使用 Tomcat/Netty 线程池**，不自行创建
3. **异步化**：用 CompletableFuture、Reactive 减少线程阻塞
4. **队列**：设置合理队列大小 + 拒绝策略（CallerRunsPolicy）
5. **池化资源**：DB 连接池、Redis 连接池匹配线程数
6. **线程隔离**：核心/非核心业务用不同线程池
7. **压测调优**：以实际压测结果为准

---

### 26. volatile 有哪些应用场景

1. **状态标志位**：
```java
volatile boolean running = true;
// 一个线程读，一个线程写
```

2. **DCL 单例模式**（双重检查锁定）：
```java
private static volatile Singleton instance;
```
防止指令重排导致未初始化完成的对象被使用。

3. **无锁计数器**（配合 CAS）：如 `AtomicInteger` 内部使用 volatile 修饰 value。

4. **happens-before 保证**：确保一个线程写入 volatile 变量，另一个线程能立即看到。

---

### 44. synchronized 怎么提升性能

JDK 6 之后引入**锁升级机制**：

1. **偏向锁**：同一线程反复获取锁，无竞争时偏向该线程，CAS 记录线程 ID
2. **轻量级锁**：有竞争时，通过 CAS 自旋尝试获取
3. **重量级锁**：自旋失败后膨胀为重量级锁，阻塞线程（OS 挂起）

**其它优化**：
- 减少锁粒度（分段锁，如 ConcurrentHashMap 1.7）
- 锁粗化：多次小锁合并成一次
- 锁消除：JIT 判断无需锁则去除
- 减小同步块范围

---

## 五、Spring Boot

### 24. SpringBoot 百万数据插入怎么优化

1. **批量插入**：`INSERT INTO t VALUES (...), (...), (...)`，每批 500~1000 条
2. **分批提交**：每 1000 条 commit 一次
3. **多线程并行**：数据分段，多线程并发插入
4. **关闭非必要功能**：临时关闭索引、binlog（从库导入场景）
5. **原生 JDBC**：不用 JPA/Hibernate（ORM 逐条插入慢）
6. **LOAD DATA INFILE**：MySQL 最快导入方式
7. **连接池放大**：增大 `hikari.maximum-pool-size`

---

### 25. SpringBoot 可以同时处理多少请求

**取决于**：
- **内嵌容器**：Tomcat 默认 200 线程（`server.tomcat.max-threads`）
- **最大连接数**：`server.tomcat.max-connections` 默认 8192
- **等待队列**：`server.tomcat.accept-count` 默认 100

**计算**：
```
理论最大并发 = max-threads + accept-count = 200 + 100 = 300
```

**调优**：
- 增大 `max-threads` 和 `accept-count`
- IO 密集型增大更多线程
- 优化业务处理速度（缩短线程占用时间）

**异步**：使用 `@Async` + 线程池，或 WebFlux 响应式，可显著提升并发。

---

### 43. RestTemplate 如何优化连接池

```java
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(200);      // 最大连接数
cm.setDefaultMaxPerRoute(50); // 每个路由最大连接数

CloseableHttpClient httpClient = HttpClients.custom()
    .setConnectionManager(cm)
    .setKeepAliveStrategy(...)  // keep-alive 策略
    .evictIdleConnections(30, TimeUnit.SECONDS) // 清理空闲连接
    .build();

RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
```

**关键参数**：
- `maxTotal`：总连接数
- `defaultMaxPerRoute`：单路由并发数
- `connectTimeout / readTimeout`：防止长时间挂起

---

### 46. 如何防止 SpringBoot 反编译

**无法完全防止，只能增加难度**：

1. **代码混淆**：ProGuard / yGuard 混淆类名、方法名
2. **字节码加密**：自定义 ClassLoader，启动时解密字节码
3. **JNI 调用核心代码**：核心逻辑用 C/C++ 编译为 .so/.dll
4. **代码分割部署**：核心服务独立部署，不暴露 JAR
5. **商业保护**：如 DexGuard、Virbox

**现实**：Java 容易被反编译，核心在于服务端控制、API 鉴权。

---

### 47. 有没有出现 Spring 正常 SpringBoot 报错的情况？

**常见情况**：

1. **自动配置冲突**：SpringBoot 自动配置的 Bean 与自定义 Bean 冲突
2. **版本不兼容**：`spring-boot-starter-parent` 管理的依赖版本与手动引入冲突
3. **包扫描范围**：主类包以外的组件未被扫描到
4. **`@ConditionalOnMissingBean`**：SpringBoot 自动配置有条件，手动配置后可能跳过
5. **Servlet 容器差异**：SpringBoot 内嵌 Tomcat 与外部 Tomcat 行为不同
6. **配置文件加载顺序**：`application.yml` 优先级可能覆盖 XML 配置

---

### 48. 怎么记录 MyBatis 的 SQL 耗时时间

**方法一：MyBatis 插件拦截器**
```java
@Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
public class SqlCostInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = invocation.proceed();
        long cost = System.currentTimeMillis() - start;
        // 记录 cost
        return result;
    }
}
```

**方法二**：Druid 自带 SQL 监控（`WallFilter` + `StatFilter`）

**方法三**：使用 `P6Spy` 代理 JDBC 驱动，记录所有 SQL 及耗时

---

### 49. 如何对 SpringBoot 配置文件敏感信息加密

**方案一：Jasypt**
```yaml
spring:
  datasource:
    password: ENC(加密后的密文)
```
配合密钥 `jasypt.encryptor.password`。

**方案二**：Spring Cloud Config + Vault

**方案三**：启动时环境变量注入（容器化）

**方案四**：自研，自定义 `PropertySourceLoader` 解密

**原则**：密钥不能写在配置文件中，通过环境变量 / KMS / Vault 管理。

---

## 六、分布式 / 微服务

### 15. 分布式集群架构下怎么保证并发安全

1. **分布式锁**：
   - Redis：`SET NX PX`（Redisson）
   - Zookeeper：临时顺序节点
   - 数据库乐观锁：版本号

2. **数据库乐观锁**：
```sql
UPDATE t SET stock = stock - 1, version = version + 1
WHERE id = 1 AND stock > 0 AND version = #{oldVersion};
```

3. **分布式事务**：Seata、RocketMQ 事务消息、本地消息表

4. **幂等设计**：唯一 ID + 状态机防重复

5. **CAS 原子操作**：Redis `DECR` 或数据库原子更新

---

### 70. 基于本地消息表实现分布式事务

**核心**：事务发起方（主动方）将业务数据 + 消息数据在同一事务中写 DB。

**流程**：
1. 主动方本地事务：执行业务操作 + 写消息表（状态=待发送）
2. 定时任务扫描消息表：发送 MQ
3. MQ 发送成功 → 更新消息表状态为已发送
4. 被动方消费 MQ → 执行本地事务 → ACK
5. 重复消费通过幂等保证

**优点**：不依赖外部事务协调器。**缺点**：侵入业务。

---

### 95. 现在有哪些流行的微服务解决方案

| 方案 | 代表 |
|------|------|
| Spring Cloud Alibaba | Nacos + Sentinel + Seata + RocketMQ |
| Spring Cloud Netflix | Eureka + Hystrix + Ribbon（已过时） |
| Kubernetes 原生 | K8s Service + Istio + Envoy（服务网格） |
| Dubbo + Nacos | 高性能 RPC 方案 |
| Go-Micro / Kitex | 字节跳动 Go 生态 |

**国内主流**：Spring Cloud Alibaba 或 Dubbo + Nacos。

---

### 96. 微服务有哪些组件

| 组件 | 功能 | 代表 |
|------|------|------|
| 注册中心 | 服务发现 | Nacos, Eureka, Consul |
| 配置中心 | 配置管理 | Nacos, Apollo |
| 网关 | 流量入口 | Gateway, Zuul, Kong |
| 负载均衡 | 客户端负载 | Ribbon, LoadBalancer |
| 远程调用 | RPC/HTTP | Feign, Dubbo, gRPC |
| 熔断降级 | 容错 | Sentinel, Hystrix |
| 限流 | 流量控制 | Sentinel |
| 分布式事务 | 数据一致性 | Seata |
| 链路追踪 | 监控 | SkyWalking, Zipkin |
| 消息队列 | 异步解耦 | RocketMQ, Kafka |

---

### 97. HTTP 和 RPC 的区别

| 对比 | HTTP/REST | RPC |
|------|-----------|-----|
| 协议 | HTTP/1.1, HTTP/2 | TCP（自定义协议） |
| 数据格式 | JSON/XML（文本） | Protobuf/Hessian（二进制） |
| 性能 | 较低（文本解析） | 高（二进制序列化） |
| 跨语言 | 天然支持 | 需各语言 SDK |
| 服务治理 | 弱 | 强（Dubbo） |
| 使用场景 | 对外 API、BFF | 内部微服务间调用 |

**趋势**：gRPC（HTTP/2 + Protobuf）兼具高性能与跨语言。

---

### 98. 有哪些负载均衡算法

1. **轮询（Round Robin）**：依次分配
2. **加权轮询**：按权重比例分配
3. **随机**：随机选择
4. **加权随机**：带权重的随机
5. **最少连接数**：选当前连接数最少的节点
6. **一致性哈希**：同 Key 路由到同节点（适合缓存）
7. **最快响应时间**：选响应最快的节点

---

### 99. CAP 原则和 BASE 原则怎么理解

**CAP 原则**（三者只能满足其二）：
- **C（Consistency）一致性**：所有节点数据一致
- **A（Availability）可用性**：总能收到非错误响应
- **P（Partition Tolerance）分区容错**：网络分区时仍能工作

**实践**：P 必须保证，在 C 和 A 间做取舍。
- CP：ZooKeeper（分区时放弃可用性）
- AP：Eureka/Nacos（分区时保证可用性，允许短暂不一致）

**BASE 原则**（对 CAP 的 AP 补充）：
- **BA（Basically Available）基本可用**：系统出故障时允许损失部分可用性
- **S（Soft State）软状态**：允许中间状态
- **E（Eventually Consistent）最终一致性**：不要求强一致，最终一致即可

---

## 七、系统设计

### 11. 秒杀系统如何设计

**核心思路**：削峰 + 异步 + 限流 + 防超卖。

**架构分层**：
1. **前端**：按钮置灰、验证码、答题、防重复提交
2. **网关**：限流（令牌桶/漏桶）、黑名单
3. **服务层**：Redis 预减库存 + Lua 原子脚本 → 放行 → MQ 异步下单
4. **数据库**：乐观锁（version）防超卖

**流程**：
```
请求 → CDN/静态化 → 网关限流 → Redis预减库存(Lua) 
→ 成功 → MQ → 异步创建订单 → DB扣减库存
→ 失败 → 返回已售罄
```

**关键点**：
- 热点数据预热到 Redis
- 动静分离，静态页面 CDN
- 数据库乐观锁兜底
- 隔离部署秒杀服务

---

### 12 / 75. 订单超时自动取消是怎么实现的？

**方案一：RocketMQ 延迟消息**
```
订单创建 → 发延迟消息（30分钟后）→ 消费时检查订单状态 → 未支付则取消
```

**方案二：Redis 过期回调 + 兜底轮询**
1. `SET order:cancel:{orderId} "" EX 1800`（30分钟 TTL）
2. 监听 `__keyevent@0__:expired` → 取消订单
3. 轮询兜底（避免回调丢失）

**方案三：定时任务扫表**
```
每 5 分钟扫一次：SELECT * FROM orders WHERE status='待支付' AND create_time < NOW() - INTERVAL 30 MINUTE
```
缺点：数据量大时慢。

**推荐**：延迟消息（RocketMQ）+ 定时扫表兜底。

---

### 13. 如何防止重复下单

1. **前端**：提交后按钮置灰，页面防抖
2. **Token 机制**：下单前申请 Token → 下单时校验 Token → 一次性使用 → 删除
3. **唯一约束**：订单号（业务单号）数据库唯一索引
4. **Redis 幂等**：`SETNX order:submit:{userId}:{skuId} 1 EX 60`
5. **分布式锁**：对用户 + 商品加锁

**核心**：前后端结合，数据库唯一索引兜底。

---

### 14. 怎么防止刷单？

1. **限制下单频率**：Redis 记录用户 X 秒内下单次数，超限拒绝
2. **风控系统**：
   - IP 频率限制、设备指纹（Device Fingerprint）
   - 用户行为分析（浏览 → 加购 → 下单路径异常检测）
3. **验证码**：滑块验证码、极验
4. **地址/手机号校验**：虚拟号段过滤、地址库校验
5. **新用户限制**：新注册 X 分钟内不能下单
6. **下单限制**：每人限购 N 件
7. **人机识别**：行为轨迹分析、鼠标轨迹

---

### 16. 扫码登录怎么实现？

**流程**：
```
1. PC 端请求后台 → 生成二维码（内含唯一 token）
2. PC 端轮询：token 是否被确认
3. 手机扫码 → 解析 token → 手机端确认（带用户会话）
4. 后台：token 状态 → 已确认，关联用户信息
5. PC 端轮询到已确认 → 拿到用户信息 → 完成登录
```

**技术方案**：
- 二维码：UUID + 短链接
- 状态管理：Redis `login:scan:{token}` → `{status, userId}`
- PC 轮询：长轮询 或 WebSocket
- 安全：token 短 TTL（如 5 分钟）、HTTPS、签名校验

---

### 17. 如何设计分布式日志存储架构

```
应用 → Filebeat/Fluentd → Kafka → Logstash → Elasticsearch → Kibana
                                    ↘ Flink → 实时告警
                                    ↘ HDFS/S3 → 冷数据归档
```

**关键设计**：
1. **采集**：Filebeat（轻量）采集日志 → Kafka 削峰
2. **传输**：Kafka 保证不丢（ACK + 持久化）
3. **处理**：Logstash/Flink 格式化、过滤、聚合
4. **存储**：
   - ES（热数据）：近 7 天日志，SSD
   - HDFS/S3（冷数据）：归档 7 天以上
5. **展示**：Kibana / Grafana
6. **告警**：ERROR 级别 → 钉钉/企微通知

**规模考量**：
- 日增 100TB → Kafka 分区数 × Broker 数
- ES 索引按天切分（`log-2024.01.01`）
- ES 使用 Hot-Warm-Cold 架构

---

### 40. 用户忘记密码，系统为什么不直接提供密码，而是要修改密码

**安全性核心原因**：

1. **数据库存的是哈希值，无法反推原文**：bcrypt/argon2 是单向哈希
2. **即使管理员也看不到原文密码**：这是基本安全原则
3. **避免传输风险**：邮件/SMS 传输原文密码有泄漏风险
4. **临时链接有时效性**：重置链接带 token + 过期时间，避免持久风险
5. **合规要求**：GDPR、等保等安全标准强制要求

**正确流程**：发送重置链接（含短 TTL token）→ 用户设新密码 → bcrypt 哈希存储。

---

### 22. 内存 200M 读取 1G 文件并统计重复内容

**核心**：分治法 + 哈希。

**方案一：哈希分片**
1. 逐行读取，每行计算哈希 % N（如 100）
2. 写入对应的小文件（如 `part_0.txt` ~ `part_99.txt`）
3. 每个小文件加载到内存统计重复（Map 计数）
4. 汇总结果

**方案二**：如果内存 200M 能装下所有不重复行
1. 使用 `HashMap<String, Integer>` 计数
2. key 做压缩/截断
3. 限制 Map 大小，超限写入磁盘暂存

**方案三**：如果允许近似
- 布隆过滤器 + 多轮扫描，牺牲精度换内存

---

### 23. 查询 200 条数据耗时 200ms，怎么在 500ms 内查询 1000 条数据

**分析**：200 条 200ms → 瓶颈可能在网络/序列化，不是数据量。

**方案**：
1. **批量查询**：一次请求查 1000 条，避免 5 次往返（RTT × 5）
2. **并行查询**：如果必须拆分，用 `CompletableFuture` 并行请求
3. **分页调优**：使用游标分页代替 OFFSET
4. **缓存**：预热缓存
5. **字段精简**：只查需要的列，减少传输
6. **压缩**：开启 gzip

**核心**：避免多次网络往返，一次查询 + 合理分页即可。

---

## 八、设计模式

### 45. 开发中有没有用设计模式，怎么用的

**常见实际应用**：

1. **策略模式**：不同支付方式（微信/支付宝/银联），`PaymentStrategy` 接口
2. **模板方法**：消息推送（定义推送流程，子类实现具体通道）
3. **责任链**：审批流、网关过滤器链、Sentinel Slot Chain
4. **观察者**：Spring Event、订单状态变更通知
5. **工厂模式**：BeanFactory、动态选择服务实现
6. **代理模式**：AOP、MyBatis Mapper 代理
7. **单例模式**：Spring Bean 默认单例
8. **装饰器**：IO 流 `BufferedInputStream`

---

### 71. 代理模式在开源框架设计中的应用

1. **Spring AOP**：JDK 动态代理（接口）/ CGLIB 代理（类）
2. **MyBatis**：Mapper 接口的代理对象（`MapperProxy`）
3. **Feign**：声明式 HTTP 客户端，基于动态代理生成实现
4. **RPC 框架**：Dubbo 消费端生成远程代理对象
5. **Hibernate 延迟加载**：Lazy 关联对象用代理延迟加载

---

### 72. 模板方法模式在开源框架设计中的应用

1. **Spring JdbcTemplate**：定义 SQL 执行流程，子步骤（连接、执行、结果处理）可定制
2. **Spring RestTemplate**：HTTP 请求执行流程
3. **AbstractApplicationContext.refresh()**：Spring 容器刷新定义了 13 个步骤
4. **Servlet**：`service()` 方法定义处理流程，`doGet()` / `doPost()` 被子类重写
5. **MyBatis BaseExecutor**：定义一级缓存/事务流程，子类实现具体查询

---

### 73. 观察者模式在开源框架设计中的应用

1. **Spring Event**：`ApplicationEvent` + `@EventListener` / `ApplicationListener`
2. **Spring Cloud Bus**：配置变更广播
3. **ZooKeeper Watcher**：节点变更通知
4. **Guava EventBus**：轻量级事件总线
5. **MQ 消息订阅**：发布-订阅模型本质是观察者模式

---

### 74. 策略模式在实际项目中的灵活应用

1. **支付路由**：根据金额/渠道费率选择（微信/支付宝/银行卡）
2. **消息推送**：根据用户设备选择（APNs/FCM/小米推送）
3. **优惠计算**：满减/折扣/赠品各自策略
4. **数据脱敏**：手机号/身份证/姓名等不同脱敏策略
5. **动态线程池**：根据任务类型选择不同线程池

**Spring 集成**：
```java
@Component
public class PaymentContext {
    @Autowired
    private Map<String, PaymentStrategy> strategyMap;
    
    public void pay(String channel) {
        strategyMap.get(channel).pay();
    }
}
```
Spring 自动将 `PaymentStrategy` 的所有实现类注入 Map，Bean 名称作为 key。

---

## 九、问题排查

### 7 / 68. 百万数据解决 OOM

1. **分页查询**：每页 10000 条 → 写入 → 释放 → 下一页
2. **流式写入**：SXSSFWorkbook（POI 流式 API），只保留窗口行在内存
   ```java
   SXSSFWorkbook wb = new SXSSFWorkbook(100); // 内存只保留 100 行
   ```
3. **EasyExcel**：阿里巴巴开源，基于逐行读取/写入，天然防 OOM
4. **异步导出**：先落文件，再通知用户下载
5. **压缩**：导出 CSV 后 gzip 压缩再转 Excel

---

### 8. 线上 OOM 怎么定位和解决

**定位流程**：

1. **JVM 参数**：预先加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dump/`
2. **拿到 dump 文件**，用 MAT（Memory Analyzer Tool）分析
3. **看支配树（Dominator Tree）**：找占用最大的对象
4. **分析 GC Root 引用链**：定位为什么没被回收
5. **常见原因**：
   - 集合持续增长不清理
   - 连接/流未关闭
   - ThreadLocal 未 remove
   - 静态变量持有大对象
   - 第三方库 bug

**线上快速恢复**：重启 + dump 分析，优先保可用性。

---

### 9 / 69. 线上突发 CPU 飙高怎么定位和解决

**定位流程**：

```bash
# 1. 找 Java 进程
top -c

# 2. 找高 CPU 线程
top -Hp {pid}

# 3. 线程 ID 转 16 进制
printf '%x\n' {tid}

# 4. 看线程堆栈
jstack {pid} | grep -A 20 {hex_tid}
```

**常见原因**：
- 死循环
- 频繁 Full GC
- 正则表达式回溯
- 大量 JSON 序列化/反序列化
- 无界队列的线程池
- 大量并发计算

**工具**：`arthas`（`dashboard`、`thread -n 3`）更方便。

---

### 10. 线上发生死锁定位 & 避免

**定位**：
```bash
# 查找死锁
jstack {pid} | grep "deadlock" -A 50

# Arthas
thread -b  # 找阻塞线程
```

**避免**：
1. **统一加锁顺序**：所有线程按相同顺序申请锁
2. **锁超时**：`tryLock(timeout)`
3. **减小锁粒度**：缩小同步块范围
4. **不使用嵌套锁**：一个锁内不申请另一个锁
5. **死锁检测**：定时检测 + 中断恢复

---

## 十、其他

### 6. 重复提交订单

同第 13 题「如何防止重复下单」。

补充：Token 机制 + 数据库唯一索引 + 前端按钮防抖。

---

### 41. 怎么用 Java 实现一个简单的消息队列

**核心组件**：
1. **Broker**：内存队列 `BlockingQueue<Message>`
2. **Producer**：生产消息
3. **Consumer**：消费消息
4. **Topic**：Map<String, BlockingQueue>

**简化实现**：
```java
public class SimpleMQ {
    private Map<String, BlockingQueue<String>> topics = new ConcurrentHashMap<>();

    public void createTopic(String topic) {
        topics.putIfAbsent(topic, new LinkedBlockingQueue<>(10000));
    }

    public void send(String topic, String msg) {
        topics.get(topic).offer(msg);
    }

    public String consume(String topic) throws InterruptedException {
        return topics.get(topic).poll(1, TimeUnit.SECONDS);
    }
}
```

**进阶**：添加持久化（文件存储）、ACK 机制、事务消息、集群。

---

### 42. Git 怎么修复线上突发 Bug

**标准流程**：

```bash
# 1. 从生产分支切出 hotfix 分支
git checkout -b hotfix/xxx origin/main

# 2. 修复 Bug，提交
git commit -m "fix: 修复xxx"

# 3. 合并到 main 和 develop
git checkout main && git merge --no-ff hotfix/xxx && git push
git checkout develop && git merge --no-ff hotfix/xxx && git push

# 4. 打标签
git tag -a v1.2.1 -m "hotfix" && git push --tags

# 5. 删除 hotfix 分支
git branch -d hotfix/xxx
```

**Git Flow / GitHub Flow** 规范保证代码追溯。

---

### 22. 内存 200M 读取 1G 文件并统计重复内容

（已在前文系统设计部分回答，此处略）

---

> **全文完，共 99 道场景题，涵盖 MySQL、Redis、JVM、并发、Spring Boot、分布式、微服务、系统设计、设计模式、问题排查等核心领域。**
