# MySQL 面试总结

> 目录：`com.axon.java.stack.mysql`

---

## 一、索引数据结构

### 1.1 为什么是 B+ 树？

| 数据结构 | 查找 | 范围查询 | 适合磁盘？ | MySQL 选它？ |
|------|:---:|:---:|:---:|:---:|
| **哈希表** | O(1) 点查 | ❌ 不支持 | ❌ | ❌ |
| **AVL/红黑树** | O(log N) | 一般 | ❌ 太高 | ❌ |
| **B 树** | O(log N) | 一般 | ✅ 矮 | ❌ |
| **B+ 树** | O(log N) | ✅ 叶子链表 | ✅ 最矮 | ✅ |

### 1.2 B 树 vs B+ 树

```
B 树：                             B+ 树：
  [10, 数据]  [20, 数据]            [10] [20]           ← 内部节点只存索引
    /    |    \                       /    \
  [5,数据] [15,数据] [25,数据]    [5,数据]→[15,数据]→[25,数据]  ← 数据全在叶子
                                    叶子之间有链表连接
```

| | B 树 | B+ 树 |
|------|------|------|
| **数据位置** | 每个节点都存数据 | **只在叶子节点存数据** |
| **内部节点** | 存索引 + 数据 | 只存索引，**扇出更大** |
| **叶子节点** | 无链表 | **双向链表** |
| **范围查询** | 需中序遍历，慢 | 链表遍历，**O(连续)** |
| **树高** | 相对高 | **更矮**，磁盘 I/O 更少 |

### 1.3 B+ 树三个核心优势

> **① 叶子链表 → 范围查询直接遍历，不用回溯。**
> **② 内部节点只存索引 → 一个节点能装更多 key，树更矮，磁盘 I/O 更少。**
> **③ 数据集中叶子 → 顺序扫描友好，磁盘预读命中率高。**

---

## 二、SQL 优化口诀

```java
// 假设 index(a,b,c)

// ✅ 能用索引
where a=3                         // 用 a
where a=3 and b=5                 // 用 a,b
where a=3 and b=5 and c=4         // 用 a,b,c
where a=3 and b like 'kk%' and c=4  // 用 a,b,c

// ❌ 不能/部分用索引
where b=3                         // 没 a，全失效
where a=3 and c=5                 // 中间 b 断了，只用 a
where a=3 and b>4 and c=5         // 范围后失效，只用 a,b
where a=3 and b like '%kk' and c=4  // % 开头，只用 a
```

### 优化口诀

```
全值匹配我最爱，最左前缀要遵守。
带头大哥不能死，中间兄弟不能断。
索引列上少计算，范围之后全失效。
like 百分写最右，覆盖索引不写星。
不等空值还有 or，索引失效要少用。
```

---

## 三、Explain 执行计划

| 字段 | 含义 | 关注点 |
|------|------|------|
| `type` | 访问类型 | **最好：`const` > `ref` > `range` > `index` > `all`（最差）** |
| `key` | 实际使用的索引 | 是否命中预期索引 |
| `key_len` | 索引长度 | 复合索引用了几列 |
| `rows` | 扫描行数 | 越小越好 |
| `Extra` | 额外信息 | `Using filesort`（要优化）、`Using temporary`（要优化）、`Using index`（好） |

---

## 四、Order By & Group By

### Order By 能用索引的条件

```sql
-- ✅ 能
order by a
order by a, b
order by a, b, c
order by a desc, b desc, c desc          -- 同升同降
where a=const order by b, c              -- a 是常量

-- ❌ 不能
order by a asc, b desc                   -- 升降不一致
where a=const order by c                 -- 丢了 b
where a in(...) order by b, c            -- 多个等值也是范围
```

### Group By

> **先排序再分组，也遵循最左前缀法则。** 索引不能用时 `where` 先过滤，再 group by。

---

## 五、小表驱动大表

| 场景 A 表 | `select * from A where id in (select id from B)` | in 子查询只执行一次 |
| B 表数据量 > A 表 | `select * from A a where exists (select 1 from B b where b.id=a.id)` | exists 外层逐行驱动内层 |

---

## 六、事务 & 隔离级别

### 6.1 ACID

| 特性 | 含义 |
|------|------|
| **原子性 Atomicity** | 全做或全不做，出错回滚。undo log 实现 |
| **一致性 Consistency** | 事务前后数据完整性不变 |
| **隔离性 Isolation** | 事务间互不干扰。MVCC + 锁实现 |
| **持久性 Durability** | 提交后数据永久保存。redo log 实现 |

### 6.2 四大并发问题

| 问题 | 描述 |
|------|------|
| **脏读** | 读到别人未提交的数据 |
| **不可重复读** | 同一次事务两次读到不同值（别人 update 了） |
| **幻读** | 同一次事务两次查到不同行数（别人 insert 了） |
| **更新丢失** | 两个事务同时更新，后提交覆盖前提交 |

### 6.3 四大隔离级别

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | MySQL 默认 |
|------|:---:|:---:|:---:|:---:|
| **Read Uncommitted** | ❌ | ❌ | ❌ | |
| **Read Committed** | ✅ | ❌ | ❌ | |
| **Repeatable Read** | ✅ | ✅ | ✅(Next-Key Lock) | ✅ |
| **Serializable** | ✅ | ✅ | ✅ | |

### 6.4 隔离级别与并发问题的对应关系

> **隔离级别就是为了解决并发问题而生的。每升一级，多消灭一个问题。**

```
                    脏读    不可重复读    幻读
                      │         │         │
Read Uncommitted  ── ✗ ────── ✗ ────── ✗   问题全有
                      │         │         │
Read Committed    ── ✓ ────── ✗ ────── ✗   只杀脏读
                      │         │         │
Repeatable Read   ── ✓ ────── ✓ ────── ✗   再杀不可重复读
                      │         │         │
Serializable      ── ✓ ────── ✓ ────── ✓   全杀光
```

> **白话逻辑链：**
> 脏读太恶心 → 升到 RC。不可重复读也不行 → 升到 RR。幻读也受不了 → 升到 Serializable，但太慢了。
> **MySQL 聪明：在 RR 级别用 Next-Key Lock 把幻读也杀了，不用升到 Serializable 也够用。所以 MySQL 默认 RR。**

脏读：读到别人未提交的数据。 
幻读：同一个事务中。两次查询，查询到不同的行数 （可能被别人insert了）
不可重复读：  同一个事务中，读取到不同的值（可能被别人update了）

更新丢失， 两个事务同时更新， 后提交的事务覆盖前提交。

### 6.5 记忆法（10 个汉字全带走）

```
ACID：原一隔久       → 原子、一致、隔离、持久
问题：脏不幻丢       → 脏读、不可重复读、幻读、更新丢失
级别：未已重串       → 读未提交、读已提交、可重复读、串行化
```

> **身体部位记忆法：**
> - 脏读 → 脏手摸了没熟的
> - 不可重复读 → 眼睛看同一行，被别人改了
> - 幻读 → 眼花，多出几行
> - 更新丢失 → 屁股挤座，后提交覆盖前提交

---

## 七、锁

### 7.1 表锁（MyISAM）

```sql
lock table mylock read;   -- 读锁（共享）：都能读，不能写
lock table mylock write;  -- 写锁（独占）：别人读都不行
```

### 7.2 行锁（InnoDB）

| 操作 | 说明 |
|------|------|
| `select ... for update` | 排他锁，其他写操作阻塞 |
| `update/delete` | 自动加排他行锁 |
| `insert` | 插入意向锁 |

**行锁排查：**

```sql
show status like 'innodb_row_lock%';
-- innodb_row_lock_current_waits  当前等待数
-- innodb_row_lock_time_avg       平均等待时间
```

### 7.3 间隙锁（Gap Lock）

**防止幻读。** 锁住记录之间的间隙，其他事务不能在该间隙插入。

```sql
-- 表有 id=5、10、15
SELECT * FROM test WHERE id BETWEEN 5 AND 15 FOR UPDATE;
-- 锁住 id(5,10]、id(10,15)，无法插入 id=7
```

```
已存数据： [5] ──间隙── [10] ──间隙── [15]
               ↑锁住了      ↑锁住了
        不能插6/7/8/9   不能插11/12/13/14
```

### 7.4 锁升级：行锁变表锁

```sql
-- ❌ age 是 varchar，但条件用了整数
UPDATE user SET name='xxx' WHERE age = 10;   -- 隐式转换 → 索引失效 → 全表锁

-- ✅ 加上引号
UPDATE user SET name='xxx' WHERE age = '10';  -- 走索引 → 行锁
```

---

## 八、慢 SQL 排查五步

```
① 观察（至少跑 1 天，看慢查询情况）
② 开启慢查询日志，设阈值（如 5 秒）
③ EXPLAIN + 慢 SQL 分析
④ SHOW PROFILE（CPU、block io 耗时细节）
⑤ 优化：改索引/改 SQL
```

### 8.1 SHOW PROFILE

```sql
set profiling = on;
show profile cpu, block io for query 3;
```

| 需优化信号 | 含义 |
|------|------|
| `converting HEAP to MyISAM` | 结果太大，内存放不下，写到磁盘了 |
| `creating tmp table` | 创建了临时表 |
| `copying to temp table on disk` | 临时表都上磁盘了 |
| `locked` | 锁等待 |

---

## 九、面试话术

### B+ 树（30 秒版）

> MySQL InnoDB 索引用 B+ 树，三个原因：① **叶子节点链表串联**，范围查询直接遍历，不用回溯；② **内部节点只存索引不存数据**，一个节点能装更多 key，树更矮，I/O 更少；③ **数据全在叶子**，顺序扫描友好。相比 B 树、哈希、红黑树，B+ 树是磁盘型数据库的最佳选择。

### SQL 优化（20 秒版）

> **最左前缀 + 范围全失效。** 带头大哥不能死，中间兄弟不能断。索引列上不算函数，like 百分号写右边，范围之后全失效，不等空值 or 少用。Explain 看 type、rows、Extra，`ALL` 和 `filesort` 必须干掉。

### 隔离级别（20 秒版）

> 四级：读未提交、读已提交、可重复读、串行化。MySQL 默认 **RR**，靠 **MVCC + Next-Key Lock** 解决了幻读。ReadView 在 RR 级别事务中只生成一次。

### 行锁 vs 间隙锁（15 秒版）

> InnoDB **行锁**锁具体记录，加索引才走行锁，类型隐式转换会导致锁升级为表锁。**间隙锁**锁记录之间的空隙，防幻读，只有在 RR 级别下才开。




数据库的四大并发问题 


脏读    读取到别人未提交的数据
不可重复读  同一个事务中， 两次查询，查询到不同的值（可能被别人update了）
幻读    同一个事务中， 两次查询， 查询到不同的行数（可能被别人插入了）
更新丢失   两个事务同时更新，后提交的事务覆盖前提交



事务的隔离级别


读未提交 ： 脏读、幻读、不可重复读的问题都存在

读取已提交：  解决脏读的问题 

可重复读 ：  解决不可重复读的问题 

序列化：  解决 脏读、幻读、不可重复读的问题 



explan 

type  const  range  index  all    

key  命中的索引值

key_len 索引的长度 

rows 扫描的行数

Extra  额外信息   using filesort  using index


**最好：`const` > `ref` > `range` > `index` > `all`（最差）** |
