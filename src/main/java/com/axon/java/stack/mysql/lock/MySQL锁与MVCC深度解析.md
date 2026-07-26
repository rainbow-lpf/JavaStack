# MySQL 锁与 MVCC 深度解析

> 面试场景：先按分类讲全局，面试官问哪个再深入哪个。本文涵盖所有锁类型 + MVCC 工作原理，逐层递进。

---

## 一、锁的分类（全局视角）

```
MySQL 锁
├── 按粒度：表锁 / 行锁 / 页锁
├── 按模式：共享锁(S) / 排他锁(X)
├── 按行为：悲观锁 / 乐观锁
├── 按算法（InnoDB行锁）：Record Lock / Gap Lock / Next-Key Lock / Insert Intention Lock
└── 特殊锁：意向锁 / AUTO-INC锁 / 元数据锁(MDL)
```

---

## 二、表级锁（Table Lock）

### 2.1 读锁（共享锁 S）

```sql
LOCK TABLE mylock READ;
```

| | Session1（持有读锁） | Session2 |
|------|------|------|
| 读 mylock 表 | ✅ | ✅ |
| 写 mylock 表 | ❌ | ❌ |
| 读/写其他表 | ❌（不能访问未锁的表） | ✅ |

> **核心：读锁是共享的，所有人都能读，但没人能写。持有锁的 session 只能操作被锁定的表。**

### 2.2 写锁（排他锁 X）

```sql
LOCK TABLE mylock WRITE;
```

| | Session1（持有写锁） | Session2 |
|------|------|------|
| 读 mylock 表 | ✅ | ❌（阻塞） |
| 写 mylock 表 | ✅ | ❌（阻塞） |
| 读/写其他表 | ❌（不能访问未锁的表） | ✅ |

> **核心：写锁是独占的，别人读都不行。写锁期间其他 session 的所有操作都会被阻塞。**

### 2.3 表锁监控

```sql
SHOW OPEN TABLES;                -- 查看哪些表被锁
SHOW STATUS LIKE 'table%';       -- 查看表锁统计
-- Table_locks_immediate：立即获取锁的次数
-- Table_locks_waited：需要等待的锁次数（值大说明锁竞争严重）
```

### 2.4 MyISAM vs InnoDB 表锁差异

| | MyISAM | InnoDB |
|------|------|------|
| 默认锁粒度 | 表锁 | 行锁 |
| 是否支持事务 | ❌ | ✅ |
| 读锁 | 共享表锁 | 共享表锁 |
| 写锁 | 排他表锁 | 排走行锁） |
| `FOR UPDATE` | 表锁 | 行锁（有索引时） |

---

## 三、行级锁（Row Lock）— InnoDB 核心

### 3.1 行锁模式

行锁也有共享(S)和排他(X)两种模式，但锁的是具体行，不是整张表。

| 操作 | 加什么锁 |
|------|------|
| `SELECT`（普通） | 不加锁，快照读 |
| `SELECT ... LOCK IN SHARE MODE` | 行共享锁（S） |
| `SELECT ... FOR UPDATE` | 行排他锁（X） |
| `INSERT` | 排他行锁 + 插入意向锁 |
| `UPDATE` | 排他行锁（X） |
| `DELETE` | 排他行锁（X） |

### 3.2 行锁兼容矩阵

| | S 锁 | X 锁 |
|------|:---:|:---:|
| **S 锁** | ✅ | ❌ |
| **X 锁** | ❌ | ❌ |

### 3.3 行锁实验（核心场景）

```sql
SET autocommit = 0;
-- 表：test_innodb_lock，有索引列 a
```

**场景一：两个事务同时写同一行**

```
Session1: UPDATE test_innodb_lock SET b='xxx' WHERE a='4';  -- 成功，未提交
Session2: UPDATE test_innodb_lock SET b='yyy' WHERE a='4';  -- 阻塞！等 Session1 提交
```

> **写同一行 → 后写者阻塞。因为 X 锁与 X 锁互斥。**

**场景二：一个写一个读同一行**

```
Session1: UPDATE test_innodb_lock SET b='xxx' WHERE a='4';  -- 成功，未提交
Session2: SELECT * FROM test_innodb_lock WHERE a='4';       -- 不阻塞！读到旧值（快照读）
```

> **InnoDB 读不阻塞写，写不阻塞读（MVCC 的功劳）。读到的是 undo log 中的旧版本。**

### 3.4 行锁监控

```sql
SHOW STATUS LIKE 'innodb_row_lock%';
```

| 指标 | 含义 |
|------|------|
| `innodb_row_lock_current_waits` | 当前正在等待锁的数量 |
| `innodb_row_lock_time` | 系统启动到现在锁定的总时长（ms） |
| `innodb_row_lock_time_avg` | 每次等待的平均时长 |
| `innodb_row_lock_time_max` | 最长的一次等待时长 |
| `innodb_row_lock_waits` | 系统启动到现在等待的总次数 |

> **`waits` 和 `time_avg` 持续增长 → 行锁竞争严重，需要优化。**

---

## 四、锁升级：行锁 → 表锁（面试高频）

### 4.1 触发条件

**核心原因：索引失效导致全表扫描，MySQL 被迫把所有行都锁上，效果等同于表锁。**

```sql
-- 表 user，age 是 VARCHAR 类型，有索引

-- ❌ 隐式类型转换 → 索引失效 → 全表扫描 → 所有行被锁
UPDATE user SET name = 'xxx' WHERE age = 10;

-- ✅ 类型匹配 → 走索引 → 只锁目标行
UPDATE user SET name = 'xxx' WHERE age = '10';
```

### 4.2 为什么会发生

```
age = 10（整数）
  ↓ MySQL 做隐式转换：CAST(age AS SIGNED) = 10
  ↓ 索引列被函数包裹 → 索引失效
  ↓ 全表扫描
  ↓ 每一行都会被 X 锁锁住
  ↓ 效果 = 表锁
```

> **面试话术：**「当 WHERE 条件发生隐式类型转换，索引失效，MySQL 不得不扫描并锁定所有行，虽然加的是行锁但效果等同于表锁。这不是锁升级机制，是索引失效导致的副作用。」

---

## 五、间隙锁（Gap Lock）— RR 级别防幻读

### 5.1 什么是间隙锁

> **锁的不是记录本身，而是记录之间的间隙（区间）。其他事务不能在这个间隙里 INSERT 新记录。**

### 5.2 间隙锁示例

```
表中数据： id = 5, 10, 15
加锁区间：
  (-∞, 5]  ← 间隙 1
  (5, 10]  ← 间隙 2  
  (10, 15] ← 间隙 3
  (15, +∞) ← 间隙 4
```

```sql
-- Session1
START TRANSACTION;
SELECT * FROM test WHERE id BETWEEN 5 AND 15 FOR UPDATE;
-- 锁住范围：(5, 10], (10, 15] 两个间隙 + 10 和 15 两条记录

-- Session2
INSERT INTO test (id, value) VALUES (7, 4);   -- ❌ 阻塞！7 在 (5, 10) 间隙中
INSERT INTO test (id, value) VALUES (16, 5);  -- ✅ 不阻塞，16 超出范围3 间隙锁的规则

| 规则 | 说明 |
|------|------|
| 唯一索引等值查询 | 退化为 Record Lock，不锁间隙 |
| 唯一索引范围查询 | 加 Next-Key Lock |
| 普通索引等值查询 | 向左加 Gap Lock，向右加 Next-Key Lock |
| 无索引 | 全表所有间隙都加 Gap Lock |

### 5.4 间隙锁只在 RR 级别生效

> RC（读已提交）级别下，间隙锁是关闭的。这也是 RC 会有幻读、RR 没有幻读的根本原因。

---

## 六、Next-Key Lock（临键锁）— InnoDB 默认行锁算法

### 6.1 定义

```
Next-Key Lock = Record Lock（锁记录） + Gap Lock（锁前间隙）
```

> **本质：锁住一条记录，同时锁住它前面的间隙。既防修改已有记录，又防插入新记录。**

### 6.2 示例

```
表中数据：id = 5, 10, 15

Next-Key Lock 锁住的范围：
  (-∞, 5]   ← 锁 id<=5 的区间 + 5 这条记录
  (5, 10]   ← 锁 5 到 10 之间的间隙 + 10 这条记录
  (10, 15]  ← 锁 10 到 15 之间的间隙 + 15 这条记录
  (15, +∞)  ← 锁 15 之后的空间（这是 Gap Lock，没有右边的记录可锁）
```

### 6.3 为什么不只在 RR 级别下默认用 Next-Key Lock？

> **RR 级别要解决幻读，单独 Record Lock 不够——别人可以在间隙插入新行，导致"幻影"。Next-Key Lock = 记录锁 + 间隙锁，连插入都给你堵死，幻读解决。**

---

## 七、插入意向锁（Insert Intention Lock）

### 7.1 定义

> 事务准备在某间隙插入时，先加「插入意向锁」。它是特殊的 Gap Lock，标记"我要在这插数据了"，不互斥（多个事务可以在同一间隙插入不同位置），但和 Gap Lock 互斥。

### 7.2 工作原理

```
间隙 (5, 10)
  ↓ Session1 想插入 id=7 → 加插入意向锁（不阻塞其他插入意图）
  ↓ Session2 想插入 id=8 → 加插入意向锁（不互斥，因为插入位置不同）
  ↓ Session3 持有 (5,10) 的 Gap Lock → 插入意向锁阻塞！
```

### 7.3 兼容性

| | Gap Lock | Insert Intention Lock | Record Lock |
|------|:---:|:---:|:---:|
| **Insert Intention** | ❌ 互斥 | ✅ 兼容 | ✅ 兼容 |

> **设计目的：在保证数据一致性的前提下，最大化并发插入能力。**

---

## 八、意向锁（Intention Lock）— 表级锁

### 8.1 为什么需要意向锁

```
问题：事务 A 要锁表（加表级 X 锁），怎么知道表里有没有行被锁？
没有意向锁 → 需要逐行检查 → 太慢
有意向锁   → 看一眼表上的意向锁就知道
```

### 8.2 两种意向锁

| 锁 | 含义 |
|------|------|
| **IS（Intention Share）** | 事务打算在某些行加 S 锁 |
| **IX（Intention eXclusive）** | 事务打算在某些行加 X 锁 |

### 8.3 加锁流程

```
① 加行锁前，先在表上加对应的意向锁（IS 或 IX）
② 其他事务加表锁时，先检查表上的意向锁
③ 意向锁之间不互斥（IS 和 IX 兼容）
```

### 8.4 兼容矩阵

| | IS | IX | S | X |
|------|:---:|:---:|:---:|:---:|
| **IS** | ✅ | ✅ | ✅ | ❌ |
| **IX** | ✅ | ✅ | ❌ | ❌ |
| **S** | ✅ | ❌ | ✅ | ❌ |
| **X** | ❌ | ❌ | ❌ | ❌ |

> **记忆：意向锁之间都兼容（都是"打算"），但 X 排他锁跟谁都互斥。**

---

## 九、AUTO-INC 锁（自增锁）

### 9.1 作用

> 保证 `AUTO_INCREMENT` 列在并发插入时值不重复。

### 9.2 工作模式（`innodb_autoinc_lock_mode`）

| 值 | 模式 | 行为 |
|------|------|------|
| 0 | 传统模式 | 所有 INSERT 加表级 AUTO-INC 锁，语句结束释放 |
| 1 | 连续模式（默认） | 已知插入行数时用轻量级互斥锁；未知行数（INSERT...SELECT）用表级 AUTO-INC 锁 |
| 2 | 交错模式 | 全部用轻量级锁，但可能导致自增值不连续（主从复制有问题） |

### 9.3 轻量级锁 vs 表级锁

```
表级 AUTO-INC 锁：语句执行期间持有 → 并发差，但值连续
轻量级互斥锁：拿到自增值就释放 → 并发好，但可能不连续
```

---

## 十、元数据锁（MDL - Metadata Lock）

### 10.1 是什么

> MySQL 5.5 引入，自动加。保证 DDL 操作时表结构不变，防止「一个线程在查询，另一个线程把表删了/改了」。

### 10.2 MDL 类型

| 操作 | 加的 MDL |
|------|------|
| `SELECT` / `INSERT` / `UPDATE` / `DELETE` | MDL 读锁（共享） |
| `ALTER TABLE` / `DROP TABLE` / `CREATE INDEX` | MDL 写锁（排他） |

### 10.3 经典坑

```sql
-- Session1 长时间运行的事务
BEGIN;
SELECT * FROM big_table;  -- 持有 MDL 读锁

-- Session2
ALTER TABLE big_table ADD COLUMN c INT;  -- 阻塞，等 MDL 写锁

-- Session3、Session4...  之后所有 SELECT 也被阻塞
-- 因为 Session2 在写锁等待队列中，后续读锁也排队等
```

> **教训：事务尽快提交，别在事务里做非 DB 操作（如 RPC 调用）。**

---

## 十一、乐观锁与悲观锁

### 11.1 对比

| | 悲观锁 | 乐观锁 |
|------|------|------|
| 实现 | `SELECT ... FOR UPDATE` | version 字段 + CAS |
| 适用 | 写冲突多 | 读多写少 |
| 性能 | 并发低（阻塞） | 并发高（无阻塞） |
| MySQL | InnoDB 行锁 | 业务层 version 号 |

### 11.2 乐观锁 SQL 示例

```sql
-- 表结构
CREATE TABLE product (
  id INT PRIMARY KEY,
  stock INT,
  version INT
);

-- 扣库存（乐观锁）
UPDATE product 
SET stock = stock - 1, version = version + 1 
WHERE id = 1 AND version = 5;  -- 只有 version 匹配才更新成功

-- 判断 affected_rows，为 0 则说明被别人抢先改了，重试
```

---

## 十二、死锁（Deadlock）

### 12.1 产生原因

```
Session1: 锁 A → 等 B     Session2: 锁 B → 等 A
              ↓                          ↓
              相互等待 → 死锁
```

### 12.2 死锁示例

```sql
-- Session1
BEGIN;
UPDATE t SET c=1 WHERE id=1;  -- 锁 id=1
UPDATE t SET c=1 WHERE id=2;  -- 等 id=2

-- Session2（同时）
BEGIN;
UPDATE t SET c=2 WHERE id=2;  -- 锁 id=2
UPDATE t SET c=2 WHERE id=1;  -- 等 id=1  → 死锁！
```

### 12.3 InnoDB 如何处理

> InnoDB 自动检测死锁，选择一个代价最小的回滚（`DEADLOCK` 错误）。可配置 `innodb_deadlock_detect=ON`（默认开启）。

### 12.4 排查

```sql
-- 查看最近一次死锁信息
SHOW ENGINE INNODB STATUS\G
-- 死锁日志在 LATEST DETECTED DEADLOCK 部分
```

### 12.5 预防策略

| 方法 | 说明 |
|------|------|
| **统一加锁顺序** | 所有事务按相同顺序获取锁（最重要） |
| **缩短事务** | 尽量在事务内少做事，尽快提交 |
| **避免大事务** | 不要一次锁太多行 |
| **适当降低隔离级别** | 如果 RC 够用就别用 RR |
| **索引优化** | 有索引 = 精确行锁；没索引 = 锁全表 → 更容易死锁 |

---

## 十三、各隔离级别下的锁行为总结

| 隔离级别 | 普通 SELECT | UPDATE/DELETE | 间隙锁 | 幻读 |
|------|------|------|:---:|:---:|
| **Read Uncommitted** | 不加锁，可能读到未提交 | 行锁 | ❌ | ❌ |
| **Read Committed** | 快照读（MVCC） | 行锁 | ❌ | ❌（会幻读） |
| **Repeatable Read** | 快照读（MVCC） | Next-Key Lock | ✅ | ✅（解决） |
| **Serializable** | 自动变 `SELECT ... LOCK IN SHARE MODE` | 行锁 | ✅ | ✅ |

> **关键记忆点：RC 级别间隙锁关闭 → 有幻读。RR 级别间隙锁开启 → 无幻读。MVCC + Next-Key Lock = RR 级别的幻读终结者。**

---

## 十四、MVCC 深度解析

### 14.1 什么是 MVCC

> **Multi-Version Concurrency Control，多版本并发控制。**
>
> 一句话：**读不阻塞写，写不阻塞读。** 每个事务看到的是数据的某个"快照"版本，而不是最新版本。通过 undo log 保留历史版本，实现高效并发。

### 14.2 MVCC 解决了什么

| 问题 | 不靠 MVCC 怎么解决 | MVCC 怎么解决 |
|------|------|------|
| 脏读 | 加锁 | 读已提交的快照 |
| 不可重复读 | 加锁 | 事务内始终读同一个快照 |
| 读-写冲突 | 读加 S 锁阻塞写 | 读快照，不阻塞写 |

> **MVCC 只工作在 RC 和 RR 两个隔离级别。RU 不适用（直接读最新），Serializable 用锁不用 MVCC。**

### 14.3 MVCC 依赖的三个核心组件

```
┌─────────────────────────────────────────────┐
│              MVCC 三件套                      │
│                                               │
│  ① 隐藏列（DB_TRX_ID / DB_ROLL_PTR / DB_ROW_ID） │
│  ② Undo Log（回滚日志，存历史版本）              │
│  ③ ReadView（读视图，决定能看到哪些版本）         │
└─────────────────────────────────────────────┘
```

### 14.4 隐藏列（每行都有，你看不到）

| 隐藏列 | 大小 | 含义 |
|------|:---:|------|
| **DB_TRX_ID** | 6 字节 | 最近一次修改（或插入）这行的事务 ID |
| **DB_ROLL_PTR** | 7 字节 | 回滚指针，指向 undo log 中的上一个版本 |
| **DB_ROW_ID** | 6 字节 | 行 ID（无主键时自动生成） |

```
行记录在磁盘上的真实结构：
┌──────────┬──────────┬──────────┬─────────────────┐
│ id  name │ DB_TRX_ID│DB_ROLL_PTR│  ...其他字段...  │
│ 1  'Tom' │   1001   │  → undo   │                 │
└──────────┴──────────┴──────────┴─────────────────┘
```

### 14.5 Undo Log 版本链

```
当前行（DB_TRX_ID=1003）：
  id=1, name='Jerry', DB_TRX_ID=1003, ROLL_PTR ──┐
                                                   ↓
  Undo Log 版本2（DB_TRX_ID=1002）：              │
    id=1, name='Bob',   DB_TRX_ID=1002, ROLL_PTR ─┐
                                                   ↓
  Undo Log 版本1（DB_TRX_ID=1001）：              │
    id=1, name='Tom',   DB_TRX_ID=1001, ROLL_PTR = NULL
```

> **每次修改，旧版本被写入 undo log，新版本写入数据页，ROLL_PTR 串起整个历史链。版本链 = 时间倒序链表。**

### 14.6 ReadView（读视图）

> **ReadView 决定了当前事务"能看到"版本链中的哪些版本。它是 MVCC 的核心判断器。**

```
ReadView 包含四个关键信息：
┌──────────────────────────────┐
│ m_ids       活跃事务 ID 列表   │  ← 创建 ReadView 时，未提交的事务
│ min_trx_id  最小活跃事务 ID   │
│ max_trx_id  下一个待分配事务 ID │  ← 等于 max(m_ids) + 1
│ creator_trx_id  创建者事务 ID │
└──────────────────────────────┘
```

### 14.7 可见性判断算法（核心）

```java
// 遍历版本链，判断版本 trx_id 是否对当前事务可见
boolean isVisible(long trxId) {
    if (trxId == creator_trx_id) {
        return true;  // 自己修改的当然可见
    }
    min_trx_id) {
        return true;  // 修改该版本的事务早已提交
    }
    if (trxId >= max_trx_id) {
        return false; // 修改该版本的事务在 ReadView 之后才开始
    }
    // trx_id 在 [min_trx_id, max_trx_id) 之间
    if (m_ids.contains(trxId)) {
        return false; // 活跃事务，未提交，不可见
    } else {
        return true;  // 已提交，可见
    }
}
```

```
可视化判断流程：

  trx_id < min_trx_id ?  ──YES──▶ ✅ 可见（早就提交了）
          │
          NO
          ▼
  trx_id >= max_trx_id ? ──YES──▶ ❌ 不可见（未来的事务）
          │
          NO
          ▼
  trx_id 在 m_ids 中？ ──YES──▶ ❌ 不可见（活跃未提交）
          │
          NO
          ▼
        ✅ 可见（已提交）
        
  + 特判：trx_id == creator_trx_id → ✅ 可见（自己改的）
```

### 14.8 RC 与 RR 的 ReadView 区别（面试必考）

| | Read Committed (RC) | Repeatable Read (RR) |
|------|------|------|
| **ReadView 生成时机** | 每次 SELECT 都创建新的 | **事务开始时创建一次** |
| **效果** | 每次都能看到最新已提交的数据 | 整个事务看到的数据一致 |
| **不可重复读** | ❌ 会发生 | ✅ 不会发生 |

```
RC 级别：
  BEGIN;
  SELECT * FROM t WHERE id=1;  ← 生成 ReadView1（看到快照 A）
  -- 其他事务 commit 了
  SELECT * FROM t WHERE id=1;  ← 生成 ReadView2（看到快照 B）→ 不一致！不可重复读！

RR 级别：
  BEGIN;
  SELECT * FROM t WHERE id=1;  ← 第一次快照读时生成 ReadView（唯一一次）
  -- 其他事务 commit 了
  SELECT * FROM t WHERE id=1;  ← 复用同一个 ReadView → 看到快照 A → 一致！
```

> **一句话：RC 每次读都刷新 ReadView（能看到别人已提交的修改），RR 整个事务复用同一个 ReadView（永远看到相同的快照）。**

### 14.9 快照读 vs 当前读（重要区分）

| | 快照读（Snapshot Read） | 当前读（Current Read） |
|------|------|------|
| **定义** | 读 undo log 中的历史版本 | 读数据页的最新版本 |
| **SQL** | `SELECT`（普通） | `SELECT ... FOR UPDATE` / `UPDATE` / `DELETE` / `INSERT` |
| **加锁** | 不加锁 | 加行锁（X 或 S） |
| **走 MVCC** | ✅ 是 | ❌ 否（读最新 + 加锁） |

> **面试官常问：「MVCC 下能读到最新数据吗？」→ 普通 SELECT 读快照，`FOR UPDATE` 读最新（当前读）。**

```
RR 级别下，MVCC + 当前读 配合解决幻读：
  
  事务 A（快照读）：                             事务 B：
  SELECT COUNT(*) FROM t WHERE id>5;            INSERT INTO t VALUES(7,'x');
  → 2 行（快照）                                  → 插入成功（因为 A 只是快照读，没加锁）
  
  事务 A（当前读）：
  SELECT COUNT(*) FROM t WHERE id>5 FOR UPDATE; 
  → 3 行 ← 幻读了？
  → 但 Next-Key Lock 在这之前已经阻止了 B 的插入！
  → 所以 RR 下幻读不会发生！✅
```

### 14.10 MVCC 完整工作流程图

```
                    ┌─────────────┐
                    │ 事务开始      │
                    └──────┬──────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ 第一次快照读 → 生成 ReadView │
              │ (RR: 唯一一次; RC: 每次重建) │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  读取数据页中的当前行      │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  DB_TRX_ID 对本事务可见？  │
              │  (走可见性判断算法)        │
              └──────┬────────┬────────┘
                     │        │
                   YES      NO
                     │        │
                     ▼        ▼
              ┌─────────┐  ┌──────────────────┐
              │ 返回该版本│  │ 沿 ROLL_PTR 回溯  │
              └─────────┘  │ 到上一个 undo 版本 │
                           │ 继续判断可见性      │
                           └──────────────────┘
```

### 14.11 MVCC 面试速记

> **「MVCC = 隐藏列 + undo log 版本链 + ReadView。RC 每次读建新 ReadView → 可能不可重复读；RR 只用第一个 ReadView → 可重复读。普通 SELECT 走快照不加锁，FOR UPDATE 走当前读加锁。MVCC + Next-Key Lock = RR 无幻读。」**

---

## 十五、锁 & MVCC 综合面试问答

### Q1: MySQL 默认隔离级别是 RR，为什么？RC 不是更轻量吗？

> MySQL 5.6 之前 binlog 只有 STATEMENT 格式，RC 会因不可重复读导致主从数据不一致。历史原因 + RR 靠 Next-Key Lock 解决了幻读，安全不损失太多性能 → 保留 RR 为默认。Oracle 默认 RC 是因为它的 undo 机制不同。

### Q2: MVCC 有什么缺点？

> 1. undo log 膨胀，需要 purge 线程清理。2. 长事务导致 undo log 堆积，占用磁盘。3. 版本链太长影响扫描性能。

### Q3: 间隙锁会导致什么问题？

> 并发插入受阻。比如锁了 (5,10) 间隙，这之间谁都不能插入。在高并发插入场景，间隙锁可能成为性能瓶颈。这也是很多公司选择 RC 而不是 RR 的原因。

### Q4: 如何减少死锁？

> ① 统一加锁顺序 ② 拆分大事务 ③ 尽早 commit ④ 索引优化（索引 → 行锁，无索引 → 间隙锁范围大 → 更容易冲突）⑤ 加 `innodb_deadlock_detect=ON` 自动检测。

---

## 十六、一句话精华速记表

| 概念 | 一句话 |
|------|------|
| **表锁-读锁** | 大家都能读，没人能写 |
| **表锁-写锁** | 独占，别人读都不行 |
| **行锁** | InnoDB，有索引才走行锁 |
| **间隙锁** | 锁区间不锁记录，防插入 |
| **Next-Key Lock** | 行锁 + 间隙锁，RR 默认算法 |
| **插入意向锁** | 告诉 MySQL "我要插这里"，互不阻塞但等 Gap Lock 放行 |
| **意向锁** | 表级标记，避免逐行检查 |
| **锁升级原因** | 隐式转换 → 索引失效 → 全表扫描 → 行锁变全表锁 |
| **MVCC** | undo log 版本链 + ReadView → 读快照不阻塞写 |
| **RC vs RR** | RC 每次建 ReadView，RR 只建一次 |
| **快照读 vs 当前读** | SELECT 读历史，FOR UPDATE 读最新加锁 |
| **死锁** | 统一加锁顺序最有效 |
