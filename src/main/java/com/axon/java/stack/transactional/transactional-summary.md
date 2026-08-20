# Spring 事务传播机制 & 失效场景 — 面试总结

> 目录：`com.axon.java.stack.transactional`

---

## 〇、声明式事务实现原理（前置：知道"为什么"，传播机制和失效才不用背）

### 第 0 层：声明式 vs 编程式

```java
// 编程式：事务边界自己写，一清二楚但到处重复
TransactionStatus ts = txManager.getTransaction(def);
try {
    dao.insert(order);
    txManager.commit(ts);
} catch (Exception e) {
    txManager.rollback(ts);
    throw e;
}

// 声明式：只"声明我要事务"，开启/提交/回滚全部消失
@Transactional
public void createOrder() {
    dao.insert(order);
}
```

**声明式事务 = 编程式事务 + AOP 封装。** `@Transactional` 只是把手写的那段壳
移到了一个切面里，没有任何魔法。

### 第 1 层：壳在哪 — TransactionInterceptor（事务切面）

```
容器启动（Boot 自动装配 TransactionAutoConfiguration）：
  注册切面 BeanFactoryTransactionAttributeSourceAdvisor
    ├─ 切点：类/方法上标了 @Transactional 的 Bean
    └─ 通知：TransactionInterceptor（事务拦截器）
        ↓
Bean 初始化后（BeanPostProcessor 介入，命中切点的 Bean 被换成代理）
        ↓
调用方调 createOrder() → 实际进入代理：
  ┌─────────── 事务的壳（切面替你织入的）────────────┐
  │ ① 解析 @Transactional 属性（传播行为、隔离级别、rollbackFor）│
  │ ② 开事务（见第 3 层）                             │
  │ ③ try { 反射调真正的 createOrder() }               │
  │      ├─ 正常返回 → 提交 commit                     │
  │      └─ 抛异常   → 按规则判断回滚 rollback          │
  │ ④ finally：清理现场、恢复被挂起的事务、归还连接       │
  └────────────────────────────────────────────────┘
```

### 第 2 层：事务由谁执行 — PlatformTransactionManager

切面只是壳，真正干活的是事务管理器（策略模式）：

```
TransactionInterceptor（切面，管流程编排）
        │ 调用
        ▼
PlatformTransactionManager（接口，管事务操作）
        │ 实现
        ▼
DataSourceTransactionManager —— 里面就是 JDBC 原生三件套：
    getTransaction()  → conn.setAutoCommit(false)
    commit()          → conn.commit()
    rollback()        → conn.rollback()
```

追到底：**Spring 事务 = 自动开关 autoCommit 的 JDBC 事务**。

### 第 3 层：灵魂 — 多个 DAO 怎么共用同一个事务？（ThreadLocal）

问题：`createOrder()` 里调了 `dao.insert()` 和 `stockDao.update()`，
两次数据库操作怎么保证在同一个 Connection、同一个事务里？

```
开事务时（getTransaction 内部）：
  ① 从连接池拿一个 Connection
  ② conn.setAutoCommit(false)
  ③ 把 Connection 绑定到当前线程：
     TransactionSynchronizationManager（内部是 ThreadLocal<Map<DataSource, Connection>>）

业务方法执行时：
  MyBatis / JdbcTemplate 拿连接 → 不直接找连接池
  → 走 DataSourceUtils.getConnection(dataSource)
  → 先查 ThreadLocal："当前线程有没有绑着的事务连接？"
      ├─ 有 → 直接复用 ★（这就是同一事务的原因）
      └─ 没 → 才去连接池拿新的（非事务执行）

事务结束时（finally）：
  commit/rollback → 解绑 ThreadLocal → 连接归还池子
```

三个推论立刻成立：

| 现象 | 解释 |
|------|------|
| 同一事务内多次 DAO 操作用同一连接 | 都从 ThreadLocal 拿的同一个 |
| 事务不能跨线程 | ThreadLocal 线程私有，子线程/线程池里拿不到 |
| REQUIRES_NEW 的"挂起" | 把当前 ThreadLocal 的连接解绑、存进挂起栈，新连接重新绑定；结束再恢复 |

### 第 4 层：回滚怎么决定

```java
// TransactionInterceptor 内部判断（简化）
catch (Throwable ex) {
    if (按 rollbackFor 规则该回滚(ex)) {   // 默认：RuntimeException 和 Error
        rollback();
    } else {
        commit();    // ★ checked 异常默认提交！（下面 4.4 失效场景的根源）
    }
    throw ex;
}
```

默认规则源自 EJB 传统：**RuntimeException 回滚、Checked Exception 提交**。
所以 `rollbackFor = Exception.class` 是工程标配。

### 一张图串起来

```
@Transactional
     │ 被切点匹配
     ▼
代理对象（IoC 容器里放的是它）
     │ 调用进入
     ▼
TransactionInterceptor（切面：begin / commit / rollback 的壳）
     │
     ├─→ PlatformTransactionManager（策略接口）
     │        └─ DataSourceTransactionManager：autoCommit=false / commit() / rollback()
     │
     ├─→ TransactionSynchronizationManager（ThreadLocal 绑定连接）
     │        └─ MyBatis 经 DataSourceUtils 拿到同一个连接 → 同一事务
     │
     └─→ 传播行为在 getTransaction() 里实现（REQUIRED 加入/新建，REQUIRES_NEW 挂起+新建...）
              → 正好接上下面的"七种传播机制"
```

> 原理一句话：**声明式事务 = AOP + ThreadLocal**。@Transactional 让 Bean 被换成代理，
> 调用时切面开事务（setAutoCommit(false)）、把连接绑到 ThreadLocal，
> 业务里的 DAO 通过 ThreadLocal 复用同一连接，正常返回 commit、按规则 rollback。
> 第四章的失效场景全部由此推出：this 调用 = 绕过代理，事务根本没开。

---

## 一、七种传播机制速记

| # | 传播行为 | 有事务时 | 无事务时 | 一句话 |
|:---:|------|------|------|------|
| 1 | **REQUIRED**（默认） | 加入 | 新建 | "必须有" |
| 2 | **SUPPORTS** | 加入 | 非事务执行 | "无所谓" |
| 3 | **MANDATORY** | 加入 | **抛异常** | "强制必须" |
| 4 | **REQUIRES_NEW** | **挂起当前，新建** | 新建 | "另起炉灶" |
| 5 | **NOT_SUPPORTED** | **挂起当前，非事务** | 非事务执行 | "别用事务" |
| 6 | **NEVER** | **抛异常** | 非事务执行 | "死也不用" |
| 7 | **NESTED** | **嵌套事务** | 新建 | "套娃" |

---

## 二、七种传播机制详细案例

### 1. REQUIRED（默认）

```java
@Transactional(propagation = Propagation.REQUIRED)
public void methodA() {
    methodB();  // 和 A 共用同一个事务，A 回滚 B 也回滚
}

@Transactional(propagation = Propagation.REQUIRED)
public void methodB() { }
```

> **记忆：** 有事务就加入，没事务就新建。最常用，99% 的情况。

---

### 2. SUPPORTS

```java
@Transactional(propagation = Propagation.SUPPORTS)
public void methodB() {
    // A 有事务就跟着，没事务就裸跑
}
```

> **记忆：** 有就行，没有也算了。查操作用这个。

---

### 3. MANDATORY

```java
@Transactional(propagation = Propagation.MANDATORY)
public void methodB() {
    // 必须有事务，直接调用抛异常
}
```

> **记忆：** 没事务就报错。兜底校验——调用方忘开事务直接炸。

---

### 4. REQUIRES_NEW

```java
@Transactional(propagation = Propagation.REQUIRED)
public void methodA() {
    // 事务1
    methodB();   // 事务1 暂停，methodB 独立事务2
    // 事务1 恢复
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void methodB() {
    // 独立事务，和 A 无关。A 回滚我不回滚
}
```

> **记忆：** 必开新事务，挂起旧的。**日志、发消息** 不受主业务回滚影响。

---

### 5. NOT_SUPPORTED

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void methodB() {
    // A 的事务被挂起，我无事务裸跑
}
```

> **记忆：** 强制不用事务。查询或第三方调用不想占用数据库连接。

---

### 6. NEVER

```java
@Transactional(propagation = Propagation.NEVER)
public void methodB() {
    // 有事务就抛异常
}
```

> **记忆：** 绝对不能在事务里被调用。

---

### 7. NESTED

```java
@Transactional(propagation = Propagation.REQUIRED)
public void methodA() {
    try {
        methodB();  // 嵌套事务，B 回滚不影响 A
    } catch (Exception e) { }
}

@Transactional(propagation = Propagation.NESTED)
public void methodB() { }
```

| | REQUIRES_NEW | NESTED |
|---|---|---|
| B 回滚对 A 的影响 | B 独立，互不影响 | B 回滚 A 不崩 |
| A 回滚对 B 的影响 | A 回滚 B 照常 | **A 回滚 B 也回滚** |
| 实现 | 新连接，独立的物理事务 | savepoint 回滚到保存点 |

> **记忆：** 父回滚我必回滚，我回滚父能兜。**只在 JDBC 上生效，JPA 不支持。**

---

## 三、速记口诀

```
Required 无就建       Supports 有无随
Mandatory 没就炸       Requires_New 另开扒
Not_Supported 裸奔    Never 有就炸
Nested 套娃它         父回滚爹杀
```

---

## 四、Spring 事务失效的 5 种场景

### 4.1 方法内部调用（this 调用）

```java
@Service
public class OrderService {
    @Transactional
    public void createOrder() { ... }

    public void processOrder() {
        this.createOrder();  // ❌ 事务失效！
        // this 是原始对象，不是代理，AOP 根本没拦截
    }
}
```

> **解决：** 自己注入自己（`@Autowired OrderService self`），调 `self.createOrder()`。

---

### 4.2 方法非 public

```java
@Transactional
private void doSave() { ... }  // ❌ CGLIB/JDK 代理只能拦截 public
```

---

### 4.3 异常被吞

```java
@Transactional
public void save() {
    try {
        db.insert();  // 抛异常
    } catch (Exception e) {
        // 没往外抛，Spring 不知道 → 不回滚！
    }
}
```

---

### 4.4 Checked Exception 默认不回滚

```java
@Transactional
public void save() throws IOException {
    throw new IOException();  // ❌ Spring 默认只回滚 RuntimeException
}

// ✅ 修复
@Transactional(rollbackFor = Exception.class)
```

---

### 4.5 数据库引擎不支持事务

```sql
-- MyISAM 不支持事务
CREATE TABLE t (id INT) ENGINE = MyISAM;  -- ❌ @Transactional 无效
```

---

## 五、失效速记表

| 场景 | 原因 | 解决 |
|------|------|------|
| this 调用 | 没走代理 | 注入自己或抽到另一个 Service |
| 非 public | AOP 拦不到 | 改成 public |
| try catch 吞异常 | Spring 没感知 | 抛出去或手动 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` |
| Checked Exception | 默认不回滚 | `@Transactional(rollbackFor = Exception.class)` |
| MyISAM 引擎 | 引擎不支持 | 换成 InnoDB |

---

## 六、面试话术

### 声明式事务原理（20 秒版）

> **AOP + ThreadLocal。** `@Transactional` 让 Bean 被替换成代理，调用时进入 `TransactionInterceptor` 切面：通过 `PlatformTransactionManager` 开事务（本质 `setAutoCommit(false)`），把 Connection 绑到 ThreadLocal，业务里的 MyBatis 经 ThreadLocal 复用同一连接，正常返回 commit、按 rollbackFor 规则 rollback。传播行为在开事务时实现。

### 传播机制（30 秒版）

> **七种传播：** REQUIRED 有就加没就建（默认）；SUPPORTS 有就跟没就裸跑；MANDATORY 没就炸；REQUIRES_NEW 另起事务挂起旧的；NOT_SUPPORTED 强制无事务；NEVER 有事务就炸；NESTED 嵌套 savepoint，我能自成一体但父回滚我必回滚。
>
> **NESTED vs REQUIRES_NEW：** NESTED 父回滚子也死，REQUIRES_NEW 父子完全独立。

> REQUIRED（瑞快儿的） 有就加没有就建立 。 
> REQUIRES_NEW（瑞快儿的-牛） 另起事务挂起旧的。
> SUPPORTS（色跑次） 有就跟没就裸跑。
> NOT_SUPPORTED（not_色泡提的） 强制无事务。
> NEVER 有事务就炸(从不支持事务)。
> NESTED（耐斯提的） 嵌套事务，我能自成一体但父回滚我必回滚。
> MANDATORY（曼的锤） 没就炸。


### 事务失效（20 秒版）

> **5 种失效：** this 调用不走代理、非 public、try catch 吞了异常、CheckedException 不设 rollbackFor、MyISAM 不支持事务。
