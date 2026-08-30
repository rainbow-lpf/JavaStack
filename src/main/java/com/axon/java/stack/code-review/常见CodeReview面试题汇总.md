# 常见 Code Review 面试题汇总

> 方法论 + 高频雷区清单，每条：代码 → 问题 → 修法

---

## 零、答题方法论（先讲套路再讲题）

面试给一段代码让你 review，按四步扫：

```
① 线程安全：有没有共享可变状态？静态变量/成员变量被并发改？
② 资源泄漏：连接/流/锁/ThreadLocal 关没关、remove 没 remove？
③ 异常处理：catch 是不是吞了？事务里有没有做不该做的事？
④ 性能/正确性：循环里查库、字符串拼接、BigDecimal 构造、集合遍历删除
```

**先报最严重的一条（线程安全/数据正确性），再报次要的（性能/规范）。**

---

## 一、线程安全类

### 1. SimpleDateFormat 静态共享

```java
private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");  // ❌
```

**问题**：SimpleDateFormat 非线程安全，并发 format/parse 会错乱甚至抛出 NumberFormatException。

**修法**：`DateTimeFormatter`（不可变，线程安全）或 ThreadLocal 隔离。

### 2. ArrayList / HashMap 被并发访问

```java
private List<String> list = new ArrayList<>();   // 多线程 add/get  ❌
private Map<String, String> map = new HashMap<>();  // JDK7 并发 resize 死循环 ❌
```

**修法**：`ConcurrentHashMap` / `CopyOnWriteArrayList` / 显式锁。

### 3. 单例 double-check 缺 volatile

```java
private static Instance instance;
public static Instance getInstance() {
    if (instance == null) {
        synchronized (X.class) {
            if (instance == null) instance = new Instance();   // ❌ 无 volatile，指令重排可见性
        }
    }
    return instance;
}
```

**修法**：`private static volatile Instance instance;` 或静态内部类/枚举单例。

---

## 二、资源与生命周期类

### 4. ThreadLocal 不 remove

```java
threadLocal.set(user);
// ... 用完没 remove ❌
```

**问题**：线程池复用线程，ThreadLocal 不清理 → 数据串号 + 内存泄漏（value 被一直强引用）。

**修法**：`try { ... } finally { threadLocal.remove(); }`。

### 5. 流/连接不关

```java
InputStream is = new FileInputStream(f);   // ❌ 没关
```

**修法**：`try-with-resources`（实现 AutoCloseable 的自动关）。

### 6. 事务里做 RPC / 慢 IO

```java
@Transactional
public void pay() {
    accountMapper.deduct();
    mqService.sendMsg(...);      // ❌ 事务内发消息/调 RPC
    httpClient.call(...);        // ❌ 锁没释放，慢调用拉长事务
}
```

**修法**：事务只包 DB 操作，RPC/MQ 挪到事务外（提交后再发），或走事务消息。

---

## 三、正确性类

### 7. BigDecimal 用 double 构造 / 金额用浮点

```java
new BigDecimal(0.1);        // ❌ 0.1000000000000000055511...
double amount = 19.9;       // ❌
```

**修法**：`new BigDecimal("0.1")`；金额用 BIGINT 分 或 DECIMAL。

### 8. foreach 遍历时删除

```java
for (String s : list) {
    if (s.isEmpty()) list.remove(s);   // ❌ ConcurrentModificationException
}
```

**修法**：`list.removeIf(String::isEmpty)` 或 `Iterator.remove()`。

### 9. Integer 用 == 比较

```java
Integer a = 128, b = 128;
if (a == b) ...    // ❌ false！-128~127 之外比的是引用
```

**修法**：`a.equals(b)` 或 `Objects.equals(a, b)`；能用基本类型就别装箱。

### 10. Arrays.asList 当可变 List 用

```java
List<String> list = Arrays.asList("a", "b");
list.add("c");     // ❌ UnsupportedOperationException（固定长度）
```

**修法**：`new ArrayList<>(Arrays.asList(...))`。

### 11. 空字符串判断用错

```java
if (s.equals("")) ...      // ❌ NPE 风险 + 漏空白串
if (s == null || s.length()==0) ...  // 漏空格
```

**修法**：`StringUtils.isBlank(s)`（null/空串/空白都判）。

### 12. subList 持有大 List 引用

```java
List<String> big = ...;
List<String> sub = big.subList(0, 10);   // sub 还引用整个 big 的数组 ❌ 内存泄漏
```

**修法**：`new ArrayList<>(big.subList(0, 10))`。

---

## 四、性能类

### 13. 循环里字符串 +=

```java
String result = "";
for (x : list) result += x;   // ❌ 每次 new 一个 String，O(n²)
```

**修法**：`StringBuilder`（单线程）/ `StringBuffer`（多线程）。

### 14. 循环里查库（N+1）

```java
for (Order o : orders) {
    User u = userMapper.selectById(o.getUserId());   // ❌ N 次查询
}
```

**修法**：批量 `selectByIds`（`WHERE id IN (...)`）一次拿全，Map 组装。

### 15. 循环里单条插入

```java
for (Item i : items) itemMapper.insert(i);   // ❌
```

**修法**：`batchInsert` 或 MyBatis `<foreach>` 拼批量 INSERT。

### 16. 全表 count + limit 深度分页

```sql
SELECT * FROM t ORDER BY id LIMIT 1000000, 20;   -- ❌ 越翻越慢
```

**修法**：游标分页 `WHERE id > #{lastId} ORDER BY id LIMIT 20`。

---

## 五、规范与安全类

### 17. 空 catch

```java
catch (Exception e) {
    // 什么都不做 ❌  或 e.printStackTrace() ❌
}
```

**修法**：记录日志 + 决定是否上抛/降级；异常绝不能静默。

### 18. SQL 字符串拼接（注入）

```java
String sql = "SELECT * FROM user WHERE name='" + name + "'";   // ❌
```

**修法**：`#{}` 预编译占位符。

### 19. 返回内部可变对象

```java
public List<String> getBanList() { return banList; }   // ❌ 暴露引用
```

**修法**：`Collections.unmodifiableList(banList)` 或防御性拷贝 `new ArrayList<>(banList)`。

### 20. 日志打敏感信息 / 大对象

```java
log.info("user: {}", user);   // ❌ 手机号/身份证明文 + toString 大对象
```

**修法**：脱敏 + 只打必要字段。

### 21. 魔法值硬编码

```java
if (order.getStatus() == 2) ...   // ❌ 2 是什么？
```

**修法**：枚举 / 常量类 `OrderStatus.PAID`。

### 22. 锁粒度过大

```java
public synchronized void handle() {   // 整个方法加锁，里面有大段不共享的 IO ❌
```

**修法**：锁最小临界区，只包共享变量读写。

---

## 六、高频追问话术

> 拿到一段代码我按四步扫：线程安全 → 资源泄漏 → 异常处理 → 性能正确性。最典型的三类雷区——把方法内临时变量写成成员变量（BanManager 那种，共享可变状态 + 并发串数据 + 越滚越长）、SimpleDateFormat/ArrayList 这类非线程安全对象当共享变量、以及 catch 吞异常和事务里发 MQ。修法原则就三条：共享状态要并发安全或彻底无状态、资源要 try-with-resources 或 finally 释放、正确性靠 CAS/唯一索引/参数化兜底而不是靠运气。
