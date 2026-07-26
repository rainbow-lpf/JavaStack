# Spring 事务传播机制 & 失效场景 — 面试总结

> 目录：`com.axon.java.stack.transactional`

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
