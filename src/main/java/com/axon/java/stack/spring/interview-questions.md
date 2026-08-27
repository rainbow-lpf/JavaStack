# 面试题合集

> JVM、分布式、Spring AOP、分库分表相关面试题

---

## 一、Full GC 频率过高——原因

### 一句话

老年代被快速填满，触发频繁 Full GC。

### 核心原因

| 原因 | 场景 | 为什么 |
|------|------|--------|
| **大对象直接进老年代** | 一次性查几十万条数据 | 超过 `-XX:PretenureSizeThreshold`，直接进老年代 |
| **长期存活对象过早晋升** | 高并发请求，每个请求处理链很长 | 对象熬过 `MaxTenuringThreshold`（默认 15 次 Minor GC），晋升到老年代 |
| **空间担保失败** | Survivor 区太小 | Survivor 装不下的对象直接进老年代 |
| **内存泄漏** | ThreadLocal 没 remove、静态集合一直 add | 老年代只增不减，GC Roots 可达，无法回收 |
| **元空间不足触发的连带 Full GC** | 动态代理类太多或 `-XX:MaxMetaspaceSize` 太小 | 元空间扩容失败 → 触发 Full GC 尝试回收 |
| **System.gc() 显式调用** | 第三方库代码里写了 `System.gc()` | 默认触发 Full GC（除非 `-XX:+DisableExplicitGC`） |
| **CMS/G1 并发失败** | 回收速度赶不上分配速度 | Concurrent Mode Failure → 退化为 Serial Old Full GC |

### 排查方向

- `jstat -gcutil <pid> 1000`：看老年代增长趋势
- 堆 dump：`jmap -dump:live,format=b,file=heap.bin <pid>` → MAT 分析
- GC 日志：`-Xlog:gc*:` → 看每次 GC 的原因和耗时

---

## 二、Metaspace 内存溢出——原因

### 一句话

方法区装不下了，元空间大小不够。

### 核心原因

| 原因 | 具体场景 |
|------|----------|
| **动态生成类太多** | CGLIB 代理、JPA/Hibernate 实体增强、MyBatis Mapper 代理、大量 `@Configuration` 的 CGLIB 子类 |
| **Groovy/JRuby 等动态语言** | 每个脚本都生成一个 Class |
| **Lambda 表达式过多** | 每个 lambda 在运行时生成一个匿名类 |
| **类加载器泄漏** | 老版本的 Tomcat 热部署、OSGI 容器反复加载卸载 bundle |
| **`-XX:MaxMetaspaceSize` 设置过小** | 默认不限制（用本地内存），但设置了太小就爆 |

### 典型报错

```
java.lang.OutOfMemoryError: Metaspace
```

### 解决

- 加大限制：`-XX:MaxMetaspaceSize=256m`
- 排查类加载：`jcmd <pid> GC.class_stats`、`-verbose:class` 看哪些类在持续加载
- Arthas 看 classloader：`classloader -t`

---

## 三、雪花算法原理

### 一句话

用 64 位 long 值，分成 5 段，在单机内自增生成全局唯一 ID。

### 结构

```
  1 位     41 位          10 位        12 位
┌────┬─────────────────┬────────────┬────────────┐
│ 0  │   毫秒时间戳     │ 机器ID+DC  │  序列号     │
└────┴─────────────────┴────────────┴────────────┘
 不用   从自定义纪元开始   5+5=10位    同毫秒内自增
        (69年不重复)     (最多1024节点)  (每毫秒4096个)
```

### 核心流程

```
① 获取当前毫秒时间戳
② 时间戳 == 上一毫秒？
   ├─ 是 → 序列号 +1 → 如果超过 4095 → 阻塞等待下一毫秒
   └─ 否 → 序列号归零
③ 拼装：(timestamp << 22) | (workerId << 12) | sequence
```

### 优缺点

| 优点 | 缺点 |
|------|------|
| 不依赖数据库，纯内存生成 | 时钟回拨可能产生重复 ID |
| 趋势递增（不是严格递增），索引友好 | workerId 需要手动分配 |
| 每秒可生成 4096 × 1000 = 409 万个 ID | |

### 时钟回拨解决方案

- 抛异常 + 等待时钟追上
- 启用备用 workerId
- 用 Redis/MySQL 记录时间戳做 backoff

---

## 四、`@Transactional` 和自定义注解——执行顺序

### 答案

取决于自定义注解用什么实现。Spring AOP 的执行顺序由 `@Order` 和代理链决定。

### 场景 A：自定义注解用 `@Aspect`（切面方式）

```
执行顺序 = @Order 值小的先执行（环绕层）

@Order(1) → 自定义切面  外层
@Order(2) → 事务切面    中层  ← @Transactional 的 Order 默认是 Integer.MAX_VALUE
你的方法                最内层
```

**默认情况下 `@Transactional` 的 Order = `Ordered.LOWEST_PRECEDENCE`（即 `Integer.MAX_VALUE`），所以自定义切面默认先于事务。**

### 场景 B：自定义注解用 Spring AOP Interceptor

和 `@Transactional` 同一个机制，谁的 `@Order` 小谁先。

### 让事务先于自定义切面执行

```java
// 方法一：降低事务的 Order
@EnableTransactionManagement(order = 1)  // 事务先

// 方法二：提高自定义切面的 Order
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)  // 自定义后
public class MyAspect { ... }
```

### 总结

| 需求 | 操作 |
|------|------|
| 自定义先于事务（默认） | 无需操作 |
| 事务先于自定义 | `@EnableTransactionManagement(order = 1)` |

---

## 五、ShardingSphere 原理 + 深度分页处理

### 原理

**一句话：在 JDBC 层做 SQL 拦截 → 改写 → 路由 → 结果归并。**

```
你的 SQL：SELECT * FROM user WHERE id = 1
    │
    ▼
ShardingSphere 拦截
    ├─ 解析 SQL → 表是 user，分片键是 id
    ├─ 路由计算 → id=1 → 属于分片 ds0.user_0
    ├─ 改写 SQL → 去掉逻辑表名，用真实表名
    └─ 执行 → 查询 ds0.user_0
    │
    ▼
结果归并 → 返回给调用方
```

### 核心组件

| 组件 | 职责 |
|------|------|
| SQL 解析 | 把 SQL 变成 AST 抽象语法树 |
| 路由引擎 | 根据分片键 + 分片算法，算出目标数据源和表 |
| SQL 改写 | 把逻辑表名替换成真实表名 |
| SQL 执行 | 并发发到各分片 |
| 结果归并 | 各分片结果聚合、排序、分组 |

### 深度分页问题

```
# 问题 SQL：SELECT * FROM order ORDER BY create_time LIMIT 1000000, 20

每条分片执行：
  ├─ ds0: SELECT * FROM order_0 WHERE ... LIMIT 1000020
  ├─ ds1: SELECT * FROM order_1 WHERE ... LIMIT 1000020
  ├─ ds2: SELECT * FROM order_2 WHERE ... LIMIT 1000020
  └─ ds3: SELECT * FROM order_3 WHERE ... LIMIT 1000020

→ 4 片 × 100 万条 = 400 万条数据拉到归并节点
→ 排序 → 取第 1000001~1000020
→ 耗时以分钟计
```

**核心矛盾：无论选哪个字段做分片键，总有查 SQL 不带这个字段，这时候就是全分片广播。**

### ShardingSphere 的解决方案

| 方案 | 原理 | 适用场景 |
|------|------|----------|
| **流式归并** | 边拉边排，不用一次性把所有分片数据加载到内存 | 解决内存问题，但不减少 IO |
| **装饰器归并** | 逐分片拉数据，不一次性拉完 | 同上 |
| **最优方案：禁止跳页，用游标分页** | `WHERE id > last_max_id LIMIT 20` | 分片键本身就是有序的（雪花算法） |
| **二次查询法** | 先在分片键上取 limit 的 id 列表，再拿 id 回表查 | 业务层配合 |

### 分片键用订单号完全可以

用订单号分库分表没问题，而且是**最常用的做法**。问题不在于"能不能"，而在于分完后，**不是所有查订单的 SQL 都带订单号：**

| 场景 | SQL | 分片键=订单号 | 分片键=用户ID |
|------|-----|:---:|:---:|
| 查单个订单 | `WHERE order_id = xxx` | ✅ 命中单分片 | ❌ 全分片广播 |
| 查用户的所有订单 | `WHERE user_id = xxx` | ❌ 全分片广播 | ✅ 命中单分片 |
| 商家查店铺订单 | `WHERE shop_id = xxx` | ❌ 全分片广播 | ❌ 全分片广播 |
| 后台运营查所有订单 | `WHERE status = 'PAID'` | ❌ 全分片广播 | ❌ 全分片广播 |

**分片键再合理也解决不了"不带分片键的查询 + 深度分页"这个组合。游标分页才是正解。**

### 推荐实践

```sql
-- 不要这样
SELECT * FROM order ORDER BY create_time LIMIT 1000000, 20

-- 改成游标分页
SELECT * FROM order WHERE id > #{lastId} ORDER BY id LIMIT 20
```

> ShardingSphere 5.x 的 Federation 引擎支持跨分片关联查询和子查询优化，但对深度分页仍然建议业务层配合游标分页。

---

## 六、生产环境偶发 Full GC 频繁/超时——没有堆快照，只有 GC 日志，如何排查？

### 一句话

先从 GC 日志判定触发原因和内存行为模式（泄漏 vs 吞吐大），再用低开销手段抓现场，根治靠 OOM 自动转储常态化。

### 第一步：榨干 GC 日志信息

用 **GCViewer / GCEasy** 解析日志，重点看四件事：

1. **GC 原因字段**

| 原因 | 含义 | 指向 |
|------|------|------|
| `Allocation Failure` | 分配失败，空间不足 | 分配过快 / 堆太小 |
| `Metadata GC Threshold` | 元空间到达阈值 | 动态类生成过多（CGLIB、Groovy、脚本引擎） |
| `System.gc()` | 显式调用 | 第三方库或 RMI 定时触发 → `-XX:+DisableExplicitGC` |
| `Ergonomics` | JVM 自适应策略抖动 | 参数配置问题 |
| `Concurrent Mode Failure` / ` Promotion Failed` | CMS 并发失败/晋升失败 | 回收速度赶不上分配速度 |

2. **每次 Full GC 回收效果**

- FGC 后老年代 baseline **逐次抬升**（阶梯型）→ 内存泄漏，迟早 OOM
- FGC 后回落到**同一条水平线**，间隔随流量缩短 → 非泄漏，流量大或堆太小

3. **晋升速率**

- Minor GC 间隔持续变短 + 老年代增速恒定 → 对象过早晋升，Survivor 配置不足
- 可计算每秒晋升 MB 数：判断该扩容还是改参

4. **Metaspace 曲线**

只涨不跌 → 类加载器泄漏

同时关联**业务时间轴**：FGC 时间点与定时任务、批处理、发布、大促请求峰值是否重合。

### 第二步：按曲线类型分叉排查

| 日志特征 | 结论 | 动作 |
|---------|------|------|
| 回收后 baseline 递增 | 泄漏 | 抓对象分布定位引用链 |
| 回收干净但间隔短 | 分配速率高 | 大对象？未分页 SQL？突发流量？ |
| FGC 回收很少 | 存活对象巨多 | 缓存/静态集合持有过量引用 |
| 堆平稳但仍 FGC | 显式 System.gc 或参数 | DisableExplicitGC / 调参 |

### 第三步：复发时抓现场（无快照的补救手段）

低风险顺序：

```bash
# 1. 每 1s 记录各代占用趋势（最轻）
jstat -gcutil <pid> 1000 >> gc.log

# 2. 对象直方图 topN（不触发 GC，开销小）
jmap -histo <pid> | head -30

# 3. 确认泄漏后再 dump
#    注意：live 参数会先触发一次 Full GC，生产慎用
jmap -dump:format=b,file=heap.hprof <pid>
```

推荐 **Arthas**：`memory`、`heapdump`、`vmtool --action getInstances`，开销可控。
MAT 分析 **dominator tree** 找 GC Root 引用链。

### 第四步：事后预防（面试加分点）

```bash
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump/
```

- OOM 时自动转储常态化配置，出事才有现场
- `jstat` 定时采集接入监控（Prometheus JMX Exporter），老年代趋势可视化提前告警
- 不等出事再查，让内存曲线先说话

### 总结话术

> 先从日志判定触发原因和内存行为模式 → 区分"泄漏"和"吞吐大"两条路径 → 无快照则用 `jstat` + `jmap -histo` 低成本抓现场，确认后再 dump → 根治靠 HeapDumpOnOOM 常态化 + 监控提前告警，而非等出事。
