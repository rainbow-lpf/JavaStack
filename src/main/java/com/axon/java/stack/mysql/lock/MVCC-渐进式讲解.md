# MySQL MVCC：从零到精通（渐进式讲解）

> 从"读-写冲突怎么解"出发，一层层揭开 MVCC 的面纱。读完你能讲清楚**MVCC 为什么存在、怎么工作、RC 和 RR 的本质区别、以及快照读 vs 当前读的坑**。

---

## 第一层：一个令人震惊的事实

```sql
-- Session1
BEGIN;
UPDATE user SET balance = 0 WHERE id = 1;  -- 余额变 0，还没提交

-- Session2（同时）
SELECT balance FROM user WHERE id = 1;     -- 结果：1000（旧值）还是 0（新值）？
```

**答案：Session2 读到的是旧值 1000，而且完全不阻塞！**

这违背直觉——按"锁"的逻辑，Session1 持有 X 行锁，Session2 读这一行应该被阻塞才对。但 InnoDB 做到了"**写不阻塞读**"。

**怎么做到的？→ MVCC。**

> **第一层悟道：MySQL 不靠锁来读，靠"快照"。别人在改，我读到的是改之前的旧版本。读写不冲突 → 高并发。**

---

## 第二层：怎么"看"到旧版本？→ Undo Log

每次修改数据，InnoDB 不是直接覆盖原数据，而是：

```
① 把旧数据复制一份，存到 undo log（回滚日志）中
② 在新数据里加一个"指针"指向旧版本
③ 新版本替换原位置
```

**一个直观例子：**

```
初始状态（磁盘上的行）：
┌──────────────────────────────────────┐
│ id=1  name='Tom'  balance=1000       │
│ 隐藏列：DB_TRX_ID=100                │
│        DB_ROLL_PTR=NULL（没有更旧版本）│
└──────────────────────────────────────┘

执行 UPDATE user SET balance=800 WHERE id=1 （事务 trx_id=200）：

步骤1：把旧版本写入 undo log
  Undo Log：
  ┌──────────────────────────────────────┐
  │ id=1  name='Tom'  balance=1000       │
  │ DB_TRX_ID=100                        │
  │ DB_ROLL_PTR=NULL                     │
  └──────────────────────────────────────┘

步骤2：更新磁盘上的行
  ┌──────────────────────────────────────┐
  │ id=1  name='Tom'  balance=800        │
  │ DB_TRX_ID=200                        │
  │ DB_ROLL_PTR──→ 指向 undo log 里的旧版本│
  └──────────────────────────────────────┘
```

**DB_ROLL_PTR（回滚指针）把新版本和旧版本串成一条链。**

> **第二层悟道：undo log 是 MVCC 的"时光机"。改数据前先把旧版本存起来，新版本指着旧版本。任何事务顺着指针就能回到过去。**

---

## 第三层：隐藏列 → 每行都有的"身份证"

你可能以为一行数据只有你建表的那些列。实际上 InnoDB 在每行后面偷偷加了三个字段：

```
你看到的：  id   name   balance
实际存储：  id   name   balance   DB_TRX_ID   DB_ROLL_PTR   DB_ROW_ID
                                   ↑            ↑            ↑
                              最后修改的     回滚指针      行ID（无主键时
                              事务ID      →指向旧版本      自动生成）
```

| 隐藏列 | 大小 | 作用 |
|------|:---:|------|
| **DB_TRX_ID** | 6 字节 | 标记"这行是哪个事务创建/修改的"。每个事务有一个递增 ID |
| **DB_ROLL_PTR** | 7 字节 | 指向 undo log 中上一个版本，形成版本链 |
| **DB_ROW_ID** | 6 字节 | 没主键时自动生成的行标识 |

**这三个隐藏列是 MVCC 的"基础设施"。**

> **第三层悟道：每行自带"谁改的"(TRX_ID) 和"改之前是什么"(ROLL_PTR)。这两个信息是 MVCC 判断版本可见性的依据。**

---

## 第四层：版本链 → undo log 串起来的"历史书"

每次 UPDATE，都会产生一个新版本，旧版本进入 undo log，形成链：

```
第一次插入（trx_id=100）：
  磁盘行：【Tom, 1000, TRX_ID=100, ROLL_PTR=NULL】

第一次 UPDATE → SET balance=800（trx_id=200）：
  Undo Log 1: 【Tom, 1000, TRX_ID=100, ROLL_PTR=NULL】
  磁盘行：    【Tom, 800,  TRX_ID=200, ROLL_PTR→Undo Log 1】

第二次 UPDATE → SET balance=500（trx_id=300）：
  Undo Log 2: 【Tom, 800,  TRX_ID=200, ROLL_PTR→Undo Log 1】
  磁盘行：    【Tom, 500,  TRX_ID=300, ROLL_PTR→Undo Log 2】
```

```
版本链（从磁盘行往前看）：
  
  磁盘行(最新)          Undo Log 2            Undo Log 1
  [500, TRX=300]  ──→  [800, TRX=200]  ──→  [1000, TRX=100]  ──→ NULL
       ↑                     ↑                     ↑
    第3版                  第2版                 第1版（初始）
```

**版本链 = 链表，最新的在磁盘上，旧的都在 undo log 里，通过 ROLL_PTR 串联。**

> **第四层悟道：三次修改 = 三条记录串成链表。undo log 不是一条，是每次改都产生一条，链在一起。版本链 = MySQL 的"时光隧道"。**

---

## 第五层：ReadView → "我能看哪些版本？"

版本链中多个版本都在那摆着。一个事务来读时，**哪些版本对它可见？哪些不可见？**

这就是 ReadView 的核心工作——**版本过滤器。**

### ReadView 包含 4 个关键信息：

```
┌─────────────────────────────────────────┐
│            ReadView 结构                  │
│                                           │
│  m_ids          = [201, 203, 205]        │
│                   ↑ 创建时所有活跃事务 ID   │
│                                           │
│  min_trx_id     = 201                    │
│                   ↑ 活跃事务中最小的 ID     │
│                                           │
│  max_trx_id     = 206                    │
│                   ↑ 下一个待分配的事务 ID   │
│                   （= max(m_ids) + 1）    │
│                                           │
│  creator_trx_id = 202                    │
│                   ↑ 创建这个 ReadView 的   │
│                     事务自己的 ID          │
└─────────────────────────────────────────┘
```

**什么时候创建 ReadView？这就是 RC 和 RR 的命门。**

> **第五层悟道：ReadView = 一个快照时刻的"事务状态记录"。它告诉你：此刻谁活跃（未提交）、谁已提交。然后拿版本链中每个版本的 TRX_ID 去对照 → 可见就返回，不可见就顺着 ROLL_PTR 找上一个版本。**

---

## 第六层：可见性判断算法 → ReadView 的核心逻辑

遍历版本链，对每个版本的 `DB_TRX_ID` 问三个问题：

```
拿到一个版本 V，它的 DB_TRX_ID = T

问1：T == creator_trx_id 吗？
     → YES：自己改的，可见 ✅

问2：T < min_trx_id 吗？
     → YES：这个版本是"很久以前"的事务改的，早就提交了，可见 ✅

问3：T >= max_trx_id 吗？
     → YES：这版本是"未来"的事务改的（ReadView 创建之后才开始的事务），不可见 ❌

不满足以上三个 → T 在 [min_trx_id, max_trx_id) 之间：
     问4：T 在 m_ids 列表中吗？
          → YES：修改这个版本的事务还活跃着，没提交，不可见 ❌
          → NO： 虽然 T 在区间内，但不在活跃列表 = 已提交，可见 ✅
```

```
流程图：

  版本 DB_TRX_ID = T
        │
        ├─ T == 自己？ ──YES── ▶ ✅ 可见
        │
        ├─ T < min_trx_id？ ──YES── ▶ ✅ 可见（老事务，已提交）
        │
        ├─ T >= max_trx_id？ ──YES── ▶ ❌ 不可见（未来的事务）
        │
        └─ m_ids 包含 T？
                │
              YES ──▶ ❌ 不可见（活跃未提交）
              NO  ──▶ ✅ 可见（已提交）
```

> **第六层悟道：四个判断，总共就四种结果。关键是理解"min_trx_id = 当前最老活跃事务"和"m_ids = 所有活跃事务"。已提交的不在 m_ids 中，可见；未提交的在 m_ids 中，不可见。**

---

## 第七层：用一个实例完整走一遍

> 下面用同一个场景，分别演示 RC 和 RR 两种隔离级别下的行为。仔细对比每一步的差异。

### 前置条件

```
表结构：user (id INT PRIMARY KEY, name VARCHAR(20), balance INT)
初始数据（磁盘行）：
  ┌─────────────────────────────────────────────┐
  │ id=1  name='Tom'  balance=1000              │
  │ DB_TRX_ID=90（历史事务，早已提交）             │
  │ DB_ROLL_PTR=NULL（没有更旧版本）              │
  └─────────────────────────────────────────────┘

系统中有三个活跃事务：
  ┌──────────┬─────────────────────────┐
  │ 事务 ID   │ 状态                    │
  ├──────────┼─────────────────────────┤
  │   201    │ 活跃（未提交，之后会做 UPDATE）│
  │   202    │ 活跃（未提交，会执行 SELECT）  │
  │   203    │ 活跃（未提交，全程旁观）      │
  └──────────┴─────────────────────────┘
```

---

### 第一步：事务 202 执行第一次 SELECT

```sql
-- 事务 202 执行：                      事务 201 执行：        事务 203：
-- SELECT * FROM user WHERE id=1;       （闲着呢）              （闲着）
```

**RR 和 RC 在这一步行为完全一样：**

```
① 事务 202 创建 ReadView（第一次快照读）：
  m_ids          = [201, 202, 203]   ← 三个都没提交
  min_trx_id     = 201               ← 最小的活跃 ID
  max_trx_id     = 204               ← 下一个待分配 ID
  creator_trx_id = 202               ← 做查询的正是自己

② 读磁盘行的 DB_TRX_ID = 90
  判断过程：
    90 == 202（自己）？    → NO
    90 < 201（min_trx_id）？ → YES！90 是早已提交的历史事务
    → ✅ 可见！

③ 返回结果：id=1, name='Tom', balance=1000

🔑 RR 和 RC 都一样：ReadView 刚建，结果都是 1000。
```

---

### 第二步：事务 201 修改数据（不提交）

```sql
-- 事务 201 执行：                      事务 202：              事务 203：
-- UPDATE user SET balance=500          （事务还开着）           （闲着）
-- WHERE id=1;
-- -- 注意：不提交！
```

**UPDATE 是当前读，不创建 ReadView，直接改数据页：**

```
改之前，磁盘行：【Tom, 1000, TRX=90,  ROLL_PTR=NULL】

事务 201 的 UPDATE：
  ① 旧版本进入 Undo Log：
     Undo_1: 【Tom, 1000, TRX=90, ROLL_PTR=NULL】

  ② 磁盘行更新：
     【Tom, 500,  TRX=201, ROLL_PTR → Undo_1】

现在版本链：
  磁盘行(最新)                Undo Log
  [500, TRX=201]  ──ROLL_PTR──→ [1000, TRX=90] ──→ NULL
```

---

### 第三步：事务 202 执行第二次 SELECT（关键分叉！）

```sql
-- 事务 202 执行：                      事务 201：              事务 203：
-- SELECT * FROM user WHERE id=1;       （已改完，未提交）       （闲着）
```

#### 🔵 RC 级别路径：

```
① RC 规则：每次快照读重建 ReadView
  重新创建 ReadView：
    m_ids          = [201, 203]  ← 202 从列表中摘出了自己；203 还是活跃的
    min_trx_id     = 201         ← 还是 201 最小
    max_trx_id     = 204         ← 没变
    creator_trx_id = 202

② 读磁盘行 DB_TRX_ID = 201
  判断过程：
    201 == 202（自己）？        → NO
    201 < 201（min_trx_id）？   → NO（等于，不小于）
    201 >= 204（max_trx_id）？  → NO
    201 在 [201, 204) 之间，查 m_ids：
      201 在 m_ids = [201, 203] 中吗？
      → YES！事务 201 还没提交！
      → ❌ 不可见！

③ 沿 ROLL_PTR 回溯到 Undo_1
  Undo_1 的 DB_TRX_ID = 90
  判断过程：
    90 == 202？    → NO
    90 < 201？     → YES！
    → ✅ 可见！

④ 返回结果：id=1, name='Tom', balance=1000（旧值）

🔑 RC 第二次读还是 1000，因为 201 没提交。
```

#### 🟡 RR 级别路径：

```
① RR 规则：不重建 ReadView，复用第一次的
  复用的 ReadView：
    m_ids          = [201, 202, 203]  ← 和第一次一模一样！
    min_trx_id     = 201
    max_trx_id     = 204
    creator_trx_id = 202

② 读磁盘行 DB_TRX_ID = 201
  判断过程：
    201 == 202（自己）？        → NO
    201 < 201（min_trx_id）？   → NO
    201 >= 204？                → NO
    201 在 m_ids = [201, 202, 203] 中吗？
      → YES！
      → ❌ 不可见！

③ 沿 ROLL_PTR 回溯到 Undo_1，DB_TRX_ID = 90
  90 < 201 → ✅ 可见！

④ 返回结果：id=1, name='Tom', balance=1000（旧值）

🔑 RR 第二次读也是 1000。到这一步 RR 和 RC 结果相同，因为 201 还没提交。
   但原因不同：RC 是重建 ReadView 后发现 201 还在活跃列表；
              RR 是复用旧 ReadView 发现 201 还在活跃列表。
```

---

### 第四步：事务 201 提交

```sql
-- 事务 201 执行：                      事务 202：              事务 203：
-- COMMIT;  ← 提交了！                  （事务还在）             （闲着）
```

**提交后，事务 201 变成"已提交状态"，但磁盘行上的 TRX_ID=201 还在：**

```
磁盘行：【Tom, 500, TRX=201, ROLL_PTR → Undo_1】
版本链没变，变化的是：201 不再是"活跃事务"了。
```

---

### 第五步：事务 202 执行第三次 SELECT（终极分叉！）

```sql
-- 事务 202 执行：                      事务 201：              事务 203：
-- SELECT * FROM user WHERE id=1;       （已提交，不存在了）      （闲着）
```

#### 🔵 RC 级别路径：

```
① RC 规则：每次快照读重建 ReadView
  重新创建 ReadView：
    m_ids          = [203]  ← 只有 203 还是活跃的！
                              201 提交了 → 不在列表中
                              202 是自己 → 不在列表中
    min_trx_id     = 203    ← 活跃事务只有 203，它就是最小值
    max_trx_id     = 204
    creator_trx_id = 202

② 读磁盘行 DB_TRX_ID = 201
  判断过程：
    201 == 202？       → NO
    201 < 203（min_trx_id）？ → YES！201 < 203
    → ✅ 可见！不需要看 m_ids，也不需要回溯 undo log！

③ 返回结果：id=1, name='Tom', balance=500 ← 看到了最新已提交的值！

🔑 RC：第三次读 = 500。因为 201 提交了，新建的 ReadView 里 201 不在 m_ids 中。
   和第二次读到的 1000 不同 → 这就是"不可重复读"！
```

#### 🟡 RR 级别路径：

```
① RR 规则：不重建 ReadView，还是用第一次那个！
  复用的 ReadView：
    m_ids          = [201, 202, 203]  ← 201 还是在这个列表里！
    min_trx_id     = 201              ← 还是 201
    max_trx_id     = 204
    creator_trx_id = 202

② 读磁盘行 DB_TRX_ID = 201
  判断过程：
    201 == 202？       → NO
    201 < 201？        → NO
    201 >= 204？       → NO
    201 在 m_ids = [201, 202, 203] 中吗？
      → YES！不管 201 实际上提交没提交，ReadView 里它还"活着"！
      → ❌ 不可见！

③ 沿 ROLL_PTR 回溯到 Undo_1，DB_TRX_ID = 90
  90 < 201 → ✅ 可见！

④ 返回结果：id=1, name='Tom', balance=1000（仍然是旧值！）

🔑 RR：第三次读 = 1000。哪怕 201 已经提交了，因为 ReadView 是第一次拍的快照，
   在快照里 201 还是活跃的。整个事务周期内，看到的永远是同一个画面。
   → 这就是"可重复读"！
```

---

### 六步对比总览

| 步骤 | 事务 202 执行的 SQL | 事务 201 状态 | RC 结果 | RR 结果 |
|:---:|------|------|:---:|:---:|
| 1 | `SELECT * FROM user WHERE id=1` | 闲 | **1000** | **1000** |
| 2 | — | `UPDATE ... SET balance=500`（未提交） | — | — |
| 3 | `SELECT * FROM user WHERE id=1` | 已改，未提交 | **1000**（重建 ReadView，201 在列表） | **1000**（复用 ReadView，201 在列表） |
| 4 | — | `COMMIT` | — | — |
| 5 | `SELECT * FROM user WHERE id=1` | 已提交 | **500** 🔴（重建，201 不在列表 → 可见） | **1000**（复用，201 仍在列表 → 不可见） |

> **第七层悟道：**
> - RC：每次重建 ReadView → 第 5 步 201 已提交不在 m_ids → 可见 → 读到 500 → 和第 3 步的 1000 不同 → **不可重复读**。
> - RR：一次拍快照，全程复用 → 第 5 步 ReadView 里 201 仍然"活跃" → 不可见 → 还是 1000 → 和第 3 步一样 → **可重复读**。

---

## 第八层：RC vs RR 的 ReadView 生成时机（全貌）

| | Read Committed (RC) | Repeatable Read (RR) |
|------|------|------|
| **ReadView 创建时机** | **每次快照读都创建新的** | **事务中第一次快照读时创建，之后复用** |
| **效果** | 每次读看到的是"此刻最新已提交" | 整个事务看到的是"事务开始时"的数据 |
| **不可重复读** | ❌ 会发生 | ✅ 不会发生 |
| **幻读** | ❌ 会发生（ReadView 新建 + 无间隙锁） | ✅ 不会发生（ReadView 固定 + 间隙锁） |
| **并发性** | 更高 | 稍低（但够用） |

```
RC 的时间线：
  BEGIN;
  SELECT ...;  ← ReadView₁（m_ids=[201,203]）
  -- 事务 201 提交了
  SELECT ...;  ← ReadView₂（m_ids=[203]）→ 201已提交，可见 → 结果变了！
  
RR 的时间线：
  BEGIN;
  SELECT ...;  ← ReadView₁（m_ids=[201,203]）→ 生成后保存
  -- 事务 201 提交了
  SELECT ...;  ← 复用 ReadView₁（m_ids=[201,203]）→ 201仍不可见 → 结果不变！
```

> **第八层悟道：RC 和 RR 的唯一区别就在"ReadView 重建还是复用"。RC 是"每次都刷新"，RR 是"一开始拍张照，后面都用这张照"。**

---

## 第九层：快照读 vs 当前读 → 两条完全不同的路径

这是面试最容易混淆的点。MVCC 只覆盖**快照读**，**当前读**不走 MVCC。

### 快照读（Snapshot Read）

```sql
SELECT * FROM user WHERE id = 1;  -- 普通 SELECT
```

- 读的是**历史版本**（undo log 中的）
- **不加锁**
- 走 MVCC → 版本链 + ReadView

### 当前读（Current Read）

```sql
SELECT * FROM user WHERE id = 1 FOR UPDATE;          -- 当前读 + X 锁
SELECT * FROM user WHERE id = 1 LOCK IN SHARE MODE;  -- 当前读 + S 锁
UPDATE user SET balance = 0 WHERE id = 1;             -- 当前读 + X 锁
DELETE FROM user WHERE id = 1;                        -- 当前读 + X 锁
INSERT INTO user VALUES (1, 'Tom', 1000);             -- 当前读（检查唯一约束）
```

- 读的是数据页的**最新版本**
- **加锁**（S 锁或 X 锁）
- 不走 MVCC

### 对比表

| | 快照读 | 当前读 |
|------|------|------|
| **读什么** | 历史版本 | 最新版本 |
| **加锁** | 不加锁 | 加锁（S/X） |
| **阻塞写** | 不阻塞 | 阻塞 |
| **用 MVCC** | ✅ 是 | ❌ 否 |
| **SQL** | 普通 `SELECT` | `FOR UPDATE` / `UPDATE` / `DELETE` / `INSERT` |

> **第九层悟道：普通 SELECT = 快照读 = MVCC = 读历史不加锁。带 FOR UPDATE 的 SELECT 和所有写操作 = 当前读 = 读最新加锁。面试官问"MVCC 能读到最新数据吗？"→ 普通 SELECT 不能，FOR UPDATE 能（但 FOR UPDATE 本身不走 MVCC）。**

---

## 第十层：RR 级别下，MVCC + Next-Key Lock 如何联手消灭幻读

幻读的定义：同一事务两次查询，结果行数不同（别人 INSERT 了）。

```
Session1（RR 级别）：
  BEGIN;
  SELECT * FROM t WHERE id > 5;  → 2 行（快照读，ReadView 固定）
  
Session2：
  INSERT INTO t VALUES (7, 'x');
  COMMIT;  → 插入成功
  
Session1：
  SELECT * FROM t WHERE id > 5;  → 还是 2 行（快照读，ReadView 不变）
  → 幻读通过 MVCC 解决了！✅
  
  但如果是当前读呢？
  SELECT * FROM t WHERE id > 5 FOR UPDATE;
  → 当前读！读最新 → 3 行 ← 幻读了？
  
  不会！因为 FOR UPDATE 触发了 Next-Key Lock：
  第一次 FOR UPDATE 时就已经锁住了间隙！
  Session2 的 INSERT 在第一步就被阻塞了！
```

```
MVCC 防幻读（快照读场景）：
  同一ReadView → 永远看到相同数据 → 幻读不会发生

Next-Key Lock 防幻读（当前读场景）：
  锁住记录+间隙 → 别人插不进来 → 幻读不会发生

两者配合：
  普通 SELECT → MVCC 搞定
  FOR UPDATE → Next-Key Lock 搞定
  → RR 级别幻读被彻底消灭 🎉
```

> **第十层悟道：MVCC 管快照读的幻读，Next-Key Lock 管当前读的幻读。二者分工，RR 级别幻读全方位无死角。**

---

## 第十一层：Purge 线程 → 版本链的"清洁工"

undo log 里的旧版本不是永远留着的。如果一个旧版本没有任何事务还需要它（所有可能看到它的事务都提交了），它就该被清理了。

```
Purge 线程做的事：
  ┌─────────────────────────────────┐
  │  检查 undo log 中的每个旧版本     │
  │  ↓                              │
  │  这个版本还有人需要吗？           │
  │  ↓ YES（有事务的 ReadView 能看见）│
  │  → 保留                          │
  │  ↓ NO（所有事务都看不到它了）      │
  │  → 删除，释放磁盘空间             │
  └─────────────────────────────────┘
```

**这解释了为什么长事务是 MVCC 的敌人：**

```
BEGIN;  -- trx_id=500
SELECT * FROM big_table;  -- 生成 ReadView，拍到快照
-- 此时 trx_id=500 的 ReadView: min_trx_id=490

-- 之后大量 UPDATE 产生很多 undo log 版本
-- 但这些版本 DB_TRX_ID ≥ 490 < 500 
-- Purge 线程：这些版本可能被事务500需要 → 不能删！
-- undo log 堆积 → 磁盘膨胀 → 甚至拖慢查询
```

> **第十一层悟道：长事务 = undo log 不能清理 = 磁盘膨胀 + 版本链变长 + 查询变慢。事务尽早提交不只是为了锁，也是为了 MVCC 健康。**

---

## 第十二层：Undo Log 的两个角色

```
Undo Log 的双重身份：

  ① 回滚角色：
     事务失败 → ROLLBACK → 顺着 ROLL_PTR 恢复旧版本数据
  
  ② MVCC 角色：
     事务查询 → 顺着 ROLL_PTR 找到可见的历史版本
```

实际上，MySQL 的 Undo Log 分为两类：

| 类型 | 存储什么 | 用途 |
|------|------|------|
| **Insert Undo Log** | INSERT 操作的回滚信息 | 事务回滚时删除插入的行。事务提交后**可以立即删除**（因为没人会读到未提交的插入） |
| **Update Undo Log** | UPDATE/DELETE 操作的回滚信息 | 事务回滚时恢复旧值。**MVCC 也要用它**，必须等 Purge 线程清理 |

> **第十二层悟道：Insert Undo 提交后立即删，因为没人要在历史版本中找还没插入的行。Update Undo 要保留到 Purge 线程确认无人需要它时才能删。这就是为什么 UPDATE 多的表 undo log 更容易膨胀。**

---

## 完整流程图：一次 SELECT 的 MVCC 之旅

```
BEGIN;
SELECT * FROM user WHERE id = 1;

  ① 隔离级别是 RC 还是 RR？
     ├─ RC：创建新 ReadView
     └─ RR：首次快照读 → 创建 ReadView 并保存；非首次 → 复用
  
  ② 从索引找到目标行（数据页上的最新版本）
  
  ③ 读取该行的隐藏列 DB_TRX_ID
  
  ④ 可见性判断：
     DB_TRX_ID 对本 ReadView 可见吗？
     ├─ ✅ 可见
     │    └─ 返回该版本数据
     │
     └─ ❌ 不可见
          └─ 沿 DB_ROLL_PTR 回溯到 undo log 中的上一版本
          └─ 重复步骤③④
          └─ 一直回溯到第一个可见版本，返回它
          └─ 如果回溯到最后都没有可见版本 → 这一行对本事务不可见 → 不返回

  ⑤ 返回结果给客户端
```

---

## 面试场景：如何一口气讲清楚 MVCC？

**面试官：说说 MVCC 的工作原理。**

> 「MVCC，多版本并发控制，核心目标是读不阻塞写、写不阻塞读。
>
> **三个核心组件**：隐藏列（DB_TRX_ID + DB_ROLL_PTR）、undo log 版本链、ReadView。
>
> 每行数据有两个隐藏列：最后一次修改的事务 ID 和指向旧版本的回滚指针。修改数据时，旧版本写入 undo log，新版本替换磁盘页，ROLL_PTR 串成版本链。
>
> 事务读数据时，创建 ReadView，记录此刻所有活跃事务 ID。遍历版本链，对每个版本用四条规则判断可见性：自己的可见，老事务已提交的可见，未来事务的不可见，活跃事务的不可见。
>
> **RC 和 RR 的关键区别**：RC 每次 SELECT 重建 ReadView → 每次能看到最新已提交 → 不可重复读会发生。RR 只在事务第一次快照读时创建 ReadView，之后复用 → 始终看到同一快照 → 数据一致。
>
> 注意区分**快照读和当前读**：普通 SELECT 走 MVCC 读历史版本不加锁，FOR UPDATE 走当前读最新版本加锁。MVCC 管快照读，Next-Key Lock 管当前读，两者配合在 RR 级别消灭幻读。
>
> 长事务是 MVCC 的大忌：undo log 不能清理 → 版本链膨胀 → 查询变慢。」

---

## 记忆口诀

```
MVCC 三板斧：隐藏列 + undo log + ReadView。
每次修改存旧版，回滚指针串成链。
读时不加锁，快照开道；FOR UPDATE 当前读，锁拿好。
RC 每次建新 View，看到新值会变调；
RR 只建一次 View，数据一致不变貌。
长事务是毒药，undo log 堆积不得了。
Purge 线程当清洁，没用的旧版全清扫。
读不阻塞写，写不阻塞读，高并发从此不难熬。
```



## 个人理解

```

MVCC：多版本并发控制，核心目标：读不阻塞写，写不阻塞读。

三板斧：
  ① 隐藏列：DB_TRX_ID（事务ID）、DB_ROLL_PTR（回滚指针）、DB_ROW_ID（无主键时自动生成）
  ② Undo Log：存旧版本，ROLL_PTR 串成版本链
  ③ ReadView：快照，决定能看到哪些版本

快照读 vs 当前读：
  普通 SELECT → 快照读 → 先读磁盘数据页最新行，不可见时才沿 ROLL_PTR 回溯 undo log
  UPDATE / DELETE / SELECT FOR UPDATE → 当前读 → 直接读最新版本 + 加锁
  INSERT → 不读任何现有行，直接插入

RC（读已提交）：
  每次 SELECT 都创建新 ReadView
  ReadView 包含：m_ids（活跃事务列表）、min_trx_id（最小活跃ID）、max_trx_id（下一个待分配ID）
  同一个事务内多次 SELECT → 多个不同 ReadView → 可能读到已提交的新值 → 不可重复读

RR（可重复读）：
  事务第一次快照读时创建 ReadView，之后复用
  同一个事务内多次 SELECT → 同一个 ReadView → 永远看到相同数据 → 可重复读
  防幻读：MVCC（快照读场景） + 间隙锁/Next-Key Lock（当前读场景）两条腿走路 



场景案例一：

BEGIN;
SELECT * FROM user WHERE id = 1;

  ① 隔离级别是 RC 还是 RR？
     ├─ RC：创建新 ReadView
     └─ RR：首次快照读 → 创建 ReadView 并保存；非首次 → 复用
  
  ② 从索引找到目标行（数据页上的最新版本）
  
  ③ 读取该行的隐藏列 DB_TRX_ID
  
  ④ 可见性判断：
     DB_TRX_ID 对本 ReadView 可见吗？
     ├─ ✅ 可见
     │    └─ 返回该版本数据
     │
     └─ ❌ 不可见
          └─ 沿 DB_ROLL_PTR 回溯到 undo log 中的上一版本
          └─ 重复步骤③④
          └─ 一直回溯到第一个可见版本，返回它
          └─ 如果回溯到最后都没有可见版本 → 这一行对本事务不可见 → 不返回

  ⑤ 返回结果给客户端





```
