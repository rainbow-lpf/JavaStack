# IoC 与 AOP — 渐进式讲解

> Spring 的两大基石。IoC 是地基（对象都由容器管），AOP 是地基上的增强（容器偷偷把对象换成代理）。
> 面试开场双雄，也是理解 Spring 其他一切（事务、Boot 自动装配）的前置知识。

---

## 一、IoC — 控制反转

### 第 0 层：没有 IoC 的世界

```java
public class OrderService {
    // 我需要谁，我自己 new
    private OrderDao dao = new OrderDao();
    private StockClient stock = new StockClient();
}
```

看起来没毛病，但三个问题藏在里面：

```
① 创建权在自己手里 → 想把 OrderDao 换成 MockDao 测试？必须改这行代码
② 依赖关系自己组装 → 依赖 10 个对象就得自己 new 10 个、按顺序拼好
③ 生命周期自己管理 → 每个 OrderService 都 new 一个 dao？单例谁保证？销毁谁管？
```

**核心矛盾：类既干业务，又当自己依赖的"工厂"——两种职责搅在一起。**

### 第 1 层：反转的是什么"控制"？

```
以前：OrderService 主动出击
      "我要 OrderDao → 我自己去 new / 去找"

IoC：  OrderService 被动接收
      "我声明我需要 OrderDao → 容器造好递给我"
```

被反转的"控制"就是：**对象的创建、组装、生命周期管理权**——从你的类手里，交给了 Spring 容器。

> 比喻：以前想吃啥自己买菜做饭（自己 new、自己组装），
> 现在是食堂/点外卖——你只"声明要什么"（菜名 = 依赖类型），做好端上来。
> **你不再关心菜是怎么来的，你只负责吃（业务逻辑）。**

### 第 2 层：DI 是 IoC 的实现手段

```java
@Component                      // 声明：把我交给容器管
public class OrderService {

    private final OrderDao dao;

    @Autowired                  // 注入：容器把造好的 dao 塞给我
    public OrderService(OrderDao dao) {
        this.dao = dao;         // 我从头到尾没 new 过任何东西
    }
}
```

容器启动时干的事：

```
扫描 @Component/@Service... → 发现有 OrderService
  → 看它构造器需要 OrderDao
  → 容器里找/创建 OrderDao
  → 反射调构造器，把 dao 塞进去
  → OrderService 这个成品放进单例池（一个 Map）
全世界的类都这样被容器组装好，彼此通过容器互相"认识"
```

#### IoC、控制反转、DI 三者的关系（概念辨析高频题）

```
控制反转（IoC）────────── 一个思想/目标："创建和装配的控制权"从类手里反转给容器
    │
    ├── 实现手段 1：依赖注入（DI）★ Spring 采用的
    │     容器主动把依赖"塞"给对象（构造器/setter/@Autowired）
    │     对象被动接收，全程不知道依赖从哪来
    │
    └── 实现手段 2：依赖查找（Dependency Lookup）
          对象主动向容器"要"依赖，如 applicationContext.getBean("orderDao")
          JNDI、早期的 EJB 就是这种
```

| 说法 | 评价 |
|------|------|
| "IoC 就是控制反转" | ✅ 恒等式，本来就是同一个词（Inversion of Control） |
| "IoC 和 DI 是一回事" | ⚠️ 不准确——DI 只是实现 IoC 的手段之一，范围更小 |
| "IoC 是思想，DI 是实现" | ✅ 面试标准答案 |

**为什么 Spring 选 DI 而不是依赖查找？** 依赖查找里对象还得"知道容器、主动去拿"
（`getBean` 写死在代码里，耦合容器）；DI 让对象完全被动——只声明需要什么，
连容器的存在都感知不到。**反转得更彻底**。
好莱坞原则：Don't call us, we'll call you（别来找我们，我们会找你）。

### 第 3 层：IoC 换来了什么

| 好处 | 体现 |
|------|------|
| **解耦** | OrderService 只依赖 `OrderDao` 接口，实现随容器换，业务代码零改动 |
| **可测试** | 测试时注入 MockDao，不用改一行源码 |
| **统一生命周期** | 单例、销毁回调，容器统一管 |
| **生态地基** | AOP、事务、Boot 自动装配，全都建立在"对象由容器管"之上 |

---

## 二、AOP — 面向切面编程

### 第 0 层：没有 AOP 的世界

```java
public void createOrder() {
    log.info("开始下单");           // 日志
    checkPermission();              // 权限
    TransactionStatus ts = begin(); // 事务
    try {
        // ↓↓ 真正的业务就这几行 ↓↓
        dao.insert(order);
        stockClient.deduct(sku);
        // ↑↑
        commit(ts);
    } catch (Exception e) { rollback(ts); throw e; }
    log.info("下单结束");           // 日志
}
```

问题一眼可见：**业务逻辑被淹没了**。而且"日志+权限+事务"这套壳，在几百个方法里
复制粘贴。想统一改个日志格式？改几百处。

这类**横跨所有业务、每个方法都要的逻辑**，叫"横切关注点（Cross-Cutting Concern）"：

```
       日志    权限    事务    监控
        │       │       │       │   ← 横着切过所有业务模块
  ──────┼───────┼───────┼───────┼──────
 订单模块 │       │       │       │
  ──────┼───────┼───────┼───────┼──────
 库存模块 │       │       │       │
  ──────┼───────┼───────┼───────┼──────
 用户模块 │       │       │       │
```

OOP 的继承是**纵向**抽公共代码（子类复用父类），对这种**横向**重复无能为力——
总不能让订单、库存、用户都去继承一个 "LogMixin" 吧？业务上根本不搭。

### 第 1 层：AOP 的解法 — 把"壳"抽出去，运行时套上来

```java
// 业务类干干净净，只剩业务
public void createOrder() {
    dao.insert(order);
    stockClient.deduct(sku);
}

// 切面：日志壳，一处定义
@Aspect
@Component
public class LogAspect {
    @Around("execution(* com.axon..*Service.*(..))")   // 切点：套到哪些方法上
    public Object log(ProceedingJoinPoint pjp) throws Throwable {
        log.info("开始 {}", pjp.getSignature());
        try {
            return pjp.proceed();          // ← 放行，执行真正的业务方法
        } finally {
            log.info("结束 {}", pjp.getSignature());
        }
    }
}
```

**效果：容器给调用方返回的不是原始对象，而是套了壳的"代理对象"**——
调用 `createOrder()` 实际先进入切面逻辑，`proceed()` 才穿透到真方法。

### 第 2 层：五个核心概念（面试必背）

| 术语 | 含义 | 上面例子里的对应 |
|------|------|----------------|
| **Aspect 切面** | 横切逻辑的载体类 | LogAspect |
| **JoinPoint 连接点** | 程序执行中可以插入的点 | 所有 Service 方法（候选） |
| **Pointcut 切点** | 从候选里真正圈中的那批 | execution 表达式匹配到的方法 |
| **Advice 通知** | 圈中之后干什么、什么时候干 | @Around/@Before/@After |
| **Weaving 织入** | 把切面套到目标上的动作 | 运行时代理（Spring） |

记忆钩子：**切面 = 切点(在哪) + 通知(干什么)**；织入是把这套规则装上去的过程。

### 第 3 层：Spring AOP 的实现 — 就是动态代理

```
容器启动 → BeanPostProcessor 在 Bean 初始化后介入：
  这个 Bean 的方法被切点匹配到了吗？
    ├─ 没有 → 返回原对象
    └─ 匹配 → 生成代理对象放进容器（之后注入的都是代理）
              ├─ 目标类实现了接口 → JDK 动态代理（基于接口反射）
              └─ 没实现接口       → CGLIB（生成目标类的子类，字节码增强）
调用方调的是代理 → 代理里先跑切面逻辑 → proceed() 反射调真对象
```

#### 经典坑：同类内部调用不走代理

```java
public void methodA() {
    this.methodB();     // ← this 是原始对象，不是代理！
}                       //    methodB 上的 @Transactional / 切面全部失效

// 必须"从外部调用"（拿到的是代理对象）才会走切面
```

这也是 `@Transactional` 失效的最常见原因之一。

#### 追问：Spring 里所有对象都是 AOP 代理吗？

**不是。大多数 Bean 都是"裸"的原始对象——只有命中了某个切点的 Bean 才会被代理。**

```
IoC 管"所有"：所有 Bean 都由容器创建、注入、管理生命周期  ← 无差别覆盖
AOP 管"部分"：只有匹配切点规则的 Bean 才被换成代理        ← 精准命中
```

判定流程（就是上面 BeanPostProcessor 那步的分支）：

```
每个 Bean 初始化完成后，AbstractAutoProxyCreator 介入：

  遍历容器里注册的所有 Advisor（切面）
      ├─ 一个都没匹配到 → 返回原对象 ★（大多数 Bean 走这条路）
      └─ 至少匹配一个   → 生成代理，之后容器里放的就是代理
```

判定标准就一条：**这个类/方法有没有被任何一个切点的表达式圈中**。
一个没有任何切面能匹配的 UserService，就是裸对象——也从来没有
"this 调用事务失效"这种烦恼，因为压根没事务。

什么情况会被代理：

| 触发源 | 本质 |
|------|------|
| `@Transactional` | 事务切面圈中了它 |
| `@Async` | 异步切面 |
| `@Cacheable / @CacheEvict` | 缓存切面 |
| `@Validated` | 校验切面 |
| 自己写的 `@Aspect` 切点命中 | 自定义切面 |
| `@Scope(proxyMode = TARGET_CLASS)` | 作用域代理（另一种机制，不是 Advisor） |
| `@Lazy` 注入点 | 懒加载代理 |

两个细节：

- **多个增强只合成一个代理**：同时有 `@Transactional` 和 `@Async`，
  不是包两层代理，而是多个 Advice 按 `@Order` 顺序织入同一个代理。
- **`@Configuration` 类也会被 CGLIB 增强**，但那不是 AOP——是拦截
  `@Bean` 方法保证单例的字节码增强，机制相似、用途不同，面试别混。

自检方法——看类名：

```java
userService.getClass().getName()
// 原始对象:     com.axon.service.UserService
// CGLIB 代理:   com.axon.service.UserService$$EnhancerBySpringCGLIB$$xxxx
// JDK 代理:     com.sun.proxy.$Proxy42
```

### 第 4 层：AOP 和 IoC 的关系（追问连接点）

> **IoC 是地基，AOP 是地基上的增强。**
> AOP 能成立的前提是"所有对象都由容器创建"——正因如此，容器才能在创建时偷偷
> 把原始对象换成代理对象，调用方毫无感知。没有 IoC，对象都是自己 new 的，
> Spring 根本没有"偷梁换柱"的机会。

`@Transactional` 本质就是 AOP：方法前开事务、异常回滚、成功提交，
全是切面替你织入的。

---

## 三、面试一句话版本

> **IoC（控制反转）**：对象的创建和依赖装配从业务代码反转给容器，通过依赖注入实现，
> 换来解耦、可测试和统一的生命周期管理。
>
> **AOP（面向切面）**：把日志、事务、权限这类横切多个模块的重复逻辑抽成切面，
> 在运行时通过动态代理织入目标方法，业务代码保持纯净。
> Spring 里 AOP 建立在 IoC 之上——正因对象由容器创建，才能被替换成代理。

### 常见连环追问

| 追问 | 答法 |
|------|------|
| IoC 和 DI 什么区别？ | IoC 是思想（控制权反转给容器），DI 是实现手段（容器把依赖塞给对象）。另一个手段是依赖查找，Spring 没用它因为反转不彻底 |
| Spring AOP 底层实现？ | 动态代理。有接口走 JDK 动态代理，没接口走 CGLIB；Boot 2.x 默认 CGLIB |
| 所有 Bean 都是代理吗？ | 不是。只有被切点命中的 Bean 才被替换成代理；IoC 管所有 Bean，AOP 只管命中的那部分 |
| @Transactional 为什么会失效？ | 同类内部 this 调用不走代理；方法非 public；异常被吞或抛 checked 异常默认不回滚 |
| IoC 和 AOP 什么关系？ | AOP 建立在 IoC 之上：正因对象由容器创建，初始化后才能被替换成代理对象 |