# MyBatis 设计模式与扩展点

> 面试三连：源码里用了哪些设计模式？扩展点有哪些？项目中真实扩展过什么？

---

## 一、设计模式清单（源码位置版）

| 模式 | MyBatis 中的位置 | 一句话说明 |
|------|-----------------|-----------|
| **建造者模式** | `SqlSessionFactoryBuilder`、`XMLConfigBuilder`、`XMLMapperBuilder` 等 BaseBuilder 家族 | 分步解析配置/构建复杂对象 |
| **工厂模式** | `SqlSessionFactory` → `DefaultSqlSessionFactory`；`LogFactory` | 统一创建 SqlSession/日志 |
| **单例（全局注册表）** | `Configuration`（全局唯一配置中心，持有所有 MappedStatement/缓存） | 一切组件的注册表 |
| **代理模式** ★ | `MapperProxy`（JDK 动态代理）实现 `InvocationHandler` | **没有实现类的 Mapper 接口怎么执行 SQL** |
| **模板方法** | `BaseExecutor#query()` 骨架，`doQuery()` 抽象 | 子类只填执行细节 |
| **策略模式** | `SimpleExecutor` / `ReuseExecutor` / `BatchExecutor` | 三种执行器策略可切换 |
| **装饰器模式** ★ | 缓存装饰器链：`LoggingCache` → `SynchronizedCache` → `LruCache` → `PerpetualCache` | 一层包一层，功能叠加 |
| **责任链（插件）** ★ | `InterceptorChain` 包裹四大对象 | 核心扩展机制 |
| **适配器模式** | `Log` 接口适配 log4j/slf4j/jdk14…；`LogFactory` 逐个探测 | 统一日志门面 |
| **组合模式** ★ | 动态 SQL：`MixedSqlNode` 组合 `IfSqlNode`/`ForEachSqlNode`/`TextSqlNode` | SqlNode 树统一 `apply()` |
| **迭代器模式** | `PropertyTokenizer`（implements Iterator） | 遍历 `user.orders[0].name` 属性路径 |
| **享元思想** | `Configuration` 内各类缓存（MappedStatement、TypeHandler 注册表） | 全局复用元数据 |

### 重点展开 2 个（必考）

#### 1. Mapper 接口为什么没有实现类也能执行 SQL——MapperProxy 代理

```
MapperScan → MapperFactoryBean（FactoryBean 模式，Spring 整合入口）
  → getObject() 时用 JDK 动态代理生成 Mapper 接口实现
     MapperProxy implements InvocationHandler
       └─ invoke() → MapperMethod
            ├─ 解析方法 → 对应 MappedStatement（SQL + 参数映射）
            └─ 转发给 SqlSession（insert/update/select）
                 └─ Executor 执行
```

**一句话：MyBatis 给 Mapper 接口生成了个代理，方法调用被拦截后翻译成 SqlSession 操作。**

#### 2. 缓存装饰器链——二级缓存的洋葱

```
new CachingExecutor 时的包装顺序：
LoggingCache → SynchronizedCache → SerializedCache(可选) → LruCache → PerpetualCache(真存数据的 Map)

每层只加一个职责：打日志 / 加锁 / 序列化 / LRU 淘汰 / 实际存储
→ 与 JDK IO 流（new BufferedInputStream(new FileInputStream(...))）同款思想
```

---

## 二、扩展点全景

| 扩展点 | 作用 | 典型应用 |
|--------|------|---------|
| **Interceptor 插件** ★ | 拦截四大对象：`Executor` / `StatementHandler` / `ParameterHandler` / `ResultSetHandler` | 分页、慢 SQL、多租户、加解密 |
| **TypeHandler** | Java 类型 ↔ JDBC 类型双向转换 | 枚举存 code、字段透明加解密、JSON 字段 |
| **KeyGenerator** | 主键生成与回填 | `Jdbc3KeyGenerator` / `SelectKeyGenerator`；自定义雪花回填 |
| **Cache 接口** | 二级缓存实现替换 | 自定义 RedisCache 替换 PerpetualCache |
| **DatabaseIdProvider** | 多数据库方言 | 同一 SQL 按 databaseId 选择不同实现 |
| **LanguageDriver** | SQL 语言驱动 | 自定义动态 SQL 模板（velocity 等第三方） |
| **ObjectFactory / ReflectorFactory** | 结果对象创建 / 反射信息缓存 | 特殊对象构造（少见，了解即可） |
| **@MapperScan 链路** | 与 Spring 整合 | `MapperScannerRegistrar`（Registrar）+ `MapperFactoryBean`（FactoryBean） |

### 插件机制原理（必背）

```
① 实现 Interceptor，@Intercepts({@Signature(type=Executor.class, method="query", args=...)})
② 配置后加入 InterceptorChain
③ Executor/StatementHandler/ParameterHandler/ResultSetHandler 创建时
   → interceptorChain.pluginAll(obj)
   → 每个插件 new 一个代理层层包裹（JDK 动态代理，Plugin implements InvocationHandler）
④ 方法调用穿过所有插件 → 责任链

四大对象拦截点：
  Executor    ：query / update          ← 一级缓存、事务层
  StatementHandler：prepare / parameterize / batch  ← SQL 改写（分页在这里）
  ParameterHandler ：getParameterObject / setParameters ← 参数加密
  ResultSetHandler ：handleResultSets   ← 结果解密 / 脱敏
```

---

## 三、项目中的真实扩展场景（举 3 个，代码级）

### 场景 1：插件做慢 SQL 监控（最常用）

拦截 `Executor.query/update`，耗时超阈值打点告警：

```java
@Intercepts({
    @Signature(type = Executor.class, method = "query",  args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class SlowSqlPlugin implements Interceptor {

    private long threshold = 500;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost > threshold) {
                log.warn("slow sql [{}ms] id={}", cost, ms.getId());
                // 接告警：Prometheus timer / 钉钉 webhook
            }
        }
    }
}
```

### 场景 2：TypeHandler 透明加解密（手机号/身份证）

查询自动解密、写入自动加密，业务代码零感知：

```java
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class AesPhoneTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String param, JdbcType jt)
            throws SQLException {
        ps.setString(i, AesUtil.encrypt(param));          // 入库加密
    }
    @Override
    public String getNullableResult(ResultSet rs, String col) throws SQLException {
        return AesUtil.decrypt(rs.getString(col));        // 出库解密
    }
}
```

配合 `ResultMap` 的 `typeHandler` 属性或全局 `type-handlers-package` 扫描。

### 场景 3：多租户插件自动拼 tenant_id

拦截 `StatementHandler#prepare`，用 JSqlParser 解析 SQL、给 where 追加 `tenant_id = ?`，参数自动填充当前租户上下文——业务 SQL 一行不用改。

### 场景 4（备选）：枚举 TypeHandler

`OrderStatus` 枚举 ↔ DB code 映射，替代 XML 里到处写 `status=1` 的魔法值：

```java
public class OrderStatusHandler extends BaseTypeHandler<OrderStatus> {
    setNonNullParameter: ps.setInt(i, param.getCode());
    getNullableResult:   OrderStatus.of(rs.getInt(col));
}
```

### 场景 5（备选）：自定义 KeyGenerator 回填雪花主键

insert 后用本地雪花生成器回填 id，替代 DB 自增（配合分库分表）。

---

## 四、与 Spring 整合的关键链路（顺带加分）

```
@MapperScan(MapperScannerRegistrar implements ImportBeanDefinitionRegistrar)   ← Spring 扩展点
  → 扫描 Mapper 接口 → 注册 MapperFactoryBean（FactoryBean 模式）
     → getObject() → sqlSession.getMapper(接口)
        → MapperProxy（JDK 动态代理）                                ← MyBatis 代理模式
```

**一句连接两个框架：@MapperScan 用 Spring 的 Registrar 扩展点，把每个 Mapper 接口注册成 FactoryBean，真正干活的是 MyBatis 的 MapperProxy 代理。

---

## 六、考前速记卡

### 必背 10 条（⭐⭐⭐）

1. **Mapper 接口没实现类却能跑 SQL**：`MapperProxy` JDK 动态代理 → 拦截 → `MapperMethod` → `SqlSession` → Executor
2. 二级缓存 = **装饰器洋葱**：`LoggingCache→SynchronizedCache→LruCache→PerpetualCache`（真存 Map 的是最里层）
3. Executor = **模板方法** + **三策略**：SIMPLE/REUSE/BATCH
4. 动态 SQL = **组合模式**：`MixedSqlNode` 组合 `IfSqlNode`/`ForEachSqlNode`/`TextSqlNode`
5. **插件 = 责任链**：`InterceptorChain` 包四大对象（Executor/StatementHandler/ParameterHandler/ResultSetHandler）
6. **分页插件改哪**：StatementHandler 的 SQL（JSqlParser 改写）；**慢 SQL 拦哪**：Executor.query/update
7. TypeHandler 双向转换：`setParameter` 加密 / `getResult` 解密，透明加解密靠它
8. 枚举映射：自定义 TypeHandler，枚举 ↔ DB code
9. 主键回填：`KeyGenerator`（Jdbc3KeyGenerator 用 `getGeneratedKeys`，自定义可接雪花）
10. `@MapperScan` 链路：`MapperScannerRegistrar`（Spring Registrar）+ `MapperFactoryBean`（FactoryBean）→ `MapperProxy`

### 追问预判（面试官下一句 → 一句话回）

| 追问 | 一句话答案 |
|------|-----------|
| 一级缓存和二级缓存区别？ | 一级：SqlSession 内，默认开；二级：namespace 级跨 SqlSession，要手动开 |
| 二级缓存为什么容易踩坑？ | 跨 SqlSession 读到脏数据——多表关联、join 另一表变更缓存不失效 |
| Mapper 代理是 JDK 还是 CGLIB？ | JDK 动态代理（接口），`MapperProxy implements InvocationHandler` |
| 插件能拦任意方法吗？ | 只能拦四大对象的指定方法，`@Signature` 里 type+method+args 三要素匹配 |
| #{} 和 ${} 区别？ | `#{}` 预编译占位防注入；`${}` 字符串拼接有注入风险，只用常量 |

### 记忆权重

- ⭐⭐⭐ 必背：1、2、5、6、7（问源码/扩展点必中）
- ⭐⭐ 理解：3、4、10（能讲清机制即可）
- ⭐ 了解：8、9（说出应用场景即可）**

---

## 五、面试总结话术

> MyBatis 源码模式感很强：Builder 家族是建造者，SqlSessionFactory 是工厂，Configuration 是全局单例注册表；Mapper 接口没有实现类也能跑 SQL，靠的是 MapperProxy 的 JDK 动态代理——方法调用被翻译成 SqlSession 操作；Executor 是模板方法，SIMPLE/REUSE/BATCH 三种执行器是策略；二级缓存是 PerpetualCache 外面包 LruCache、SynchronizedCache 的装饰器链；动态 SQL 的 IfSqlNode/ForEachSqlNode 组成 MixedSqlNode 是组合模式。扩展点最核心的是插件——InterceptorChain 用动态代理把四大对象层层包裹，责任链执行；项目里我用它做过慢 SQL 监控、多租户 tenant_id 自动改写，TypeHandler 做过手机号透明加解密和枚举映射。和 Spring 的连接点：@MapperScan 是 ImportBeanDefinitionRegistrar + MapperFactoryBean，底层再交给 MapperProxy。
