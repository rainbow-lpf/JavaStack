# Spring & Spring Boot — 面试总结

> 目录：`com.axon.java.stack.spring`

---

## 一、Spring 底层执行原理

### 八大步骤

```
① 容器初始化 → ② 读取 BeanDefinition → ③ Bean 实例化 → ④ 依赖注入
→ ⑤ 生命周期回调 → ⑥ AOP 代理增强 → ⑦ 事件发布 → ⑧ 事务管理
```

| 步骤 | 核心动作 |
|------|------|
| **容器初始化** | `new AnnotationConfigApplicationContext()` 启动 |
| **解析 Bean 定义** | 扫描 `@Component`/`@Service` 等 → `BeanDefinition` |
| **Bean 实例化** | 反射 `Constructor.newInstance()` |
| **依赖注入** | `@Autowired`、构造器注入、setter 注入 |
| **初始化回调** | `@PostConstruct` → `InitializingBean.afterPropertiesSet()` |
| **AOP 代理** | 创建 JDK 动态代理 / CGLIB 代理 |
| **事件机制** | `ApplicationEventPublisher` + `@EventListener` |
| **销毁** | `@PreDestroy` → `DisposableBean.destroy()` |

---



Spring Bean 的生命周期从容器启动到销毁，经历了以下完整流程：

```
1. 加载 XML / 注解配置 → 
2. 解析并注册 BeanDefinition → 
3. 执行 BeanFactoryPostProcessor → 
4. 添加感知对象 ApplicationContextAwareProcessor → 
5. 注册 BeanPostProcessor → 
6. 初始化事件广播器 → 
7. 注册事件监听器 → 
8. 创建 Bean 实例
    8.1 实例化 Bean（通过反射/CGLIB）
    8.2 BeanPostProcessor 处理 @Autowired/@Value → 属性填充
    8.3 执行 Aware 回调（BeanNameAware → BeanClassLoaderAware → BeanFactoryAware）
    8.4 BeanPostProcessor.postProcessBeforeInitialization()
    8.5 InitializingBean.afterPropertiesSet() / init-method
    8.6 BeanPostProcessor.postProcessAfterInitialization()（AOP 代理生成在此）
    8.7 注册 DisposableBean 销毁回调
9. 容器刷新完成 → 发布 ContextRefreshedEvent
```

## 二、Spring 循环依赖 — 三级缓存

### 核心：只解决 setter/字段注入的循环依赖，构造器注入不行

```java
@Service
class AService {
    @Autowired BService b;   // A 依赖 B
}
@Service
class BService {
    @Autowired AService a;   // B 依赖 A
}
```

### 三级缓存

| 缓存 | 官方名 | 存什么 |
|:---:|------|------|
| 一级 | `singletonObjects` | 完全初始化好的 Bean |
| 二级 | `earlySingletonObjects` | 提前曝光的半成品 Bean（属性未填充） |
| 三级 | `singletonFactories` | Bean 工厂（能提前生成代理对象） |

### 解决流程（A ↔ B）

```
① getBean(A) → 一级没有 → 实例化 A → 三级缓存放入 A 的工厂
                                        └→ (将来可以产生 A 的代理对象)

② 填充 A 的属性 → 发现需要 B → getBean(B)

③ 一级没有B → 实例化 B → 三级缓存放入 B 的工厂

④ 填充 B 的属性 → 发现需要 A
   → 从三级缓存取出 A 的工厂 → 调用工厂生成 A 的代理
   → 代理 A 放入二级缓存
   → 代理 A 注入给 B

⑤ B 初始化完成 → B 移入一级缓存，移除 B 的三级缓存

⑥ 回到 A：从一级拿到 B，注入给 A
    A 初始化完成 → A 移入一级，删除二级中的 A
```

**面试精简版（直接背）：**

```
A依赖B，B依赖A的问题解决。通过三级缓存：

1. 先实例化 A，再把 A 的工厂信息放入三级缓存中
2. 去填充 A 的属性值，发现有依赖 B
3. 实例化 B，再把 B 的工厂信息放入三级缓存中
4. 继续填充 B 的属性值，发现又依赖 A
5. 此时从三级缓存中取出 A 的工厂，调它拿到 A 的对象
   → 将 A 的对象放入二级缓存，移除 A 的三级缓存
   → 将 A 注入给 B，B 完成初始化，B 加入一级缓存，移除 B 的三级缓存
6. 继续填充 A 的属性值，从一级缓存拿到 B 的对象注入给 A
   → A 完成初始化，A 加入一级缓存，移除二级缓存中的 A
```

> **关键：三级缓存存的是"工厂"，不是代理对象。调用工厂才拿到对象——不需要 AOP 返回原对象，需要 AOP 返回代理。**
>
> **A 的路径：三级 → 二级 → 一级（B 需要提前拿到 A 的半成品）。**
> **B 的路径：三级 → 一级（没人需要提前拿 B 的半成品，跳过二级直接进一级）。**

### A、B 路径对比图

```
        A                            B
   实例化 A                        实例化 B
     │                               │
     ▼                               ▼
  三级缓存（工厂）                三级缓存（工厂）
     │                               │
     │    ┌──── 填充 B 需要 A ────→ getBean(A)
     │    │                          │
     ▼    │                          ▼
  二级缓存 ← 取工厂拿到 A          A 注入给 B
     │                              │
     │                              ▼
     ▼                           一级缓存 ← B 直接进
  一级缓存 ← A 从一级拿 B
```

---

## 三、Spring Boot 自动装配

### 入口

```java
@SpringBootApplication  // 组合了 3 个注解
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### `@SpringBootApplication` 拆解

| 组成注解 | 作用 |
|------|------|
| `@Configuration` | 标记这是一个配置类 |
| `@ComponentScan` | 扫描当前包下所有 Bean |
| `@EnableAutoConfiguration` | **核心**：开启自动装配 |

### 自动装配核心流程

```
@EnableAutoConfiguration
        │
        ▼
@Import(AutoConfigurationImportSelector.class)
        │
        ▼
读取 spring.factories 中 key=org.springframework.boot.autoconfigure.EnableAutoConfiguration
        │
        ▼
加载 所有 xxxAutoConfiguration 类（如 DataSourceAutoConfiguration）
        │
        ▼
@Conditional 系列注解判断是否生效
  ├─ @ConditionalOnClass         → classpath 有某个类才装
  ├─ @ConditionalOnMissingBean   → 容器里没有才装
  ├─ @ConditionalOnProperty      → 配置文件有某个属性才装
  ├─ @ConditionalOnBean          → 容器有某个 Bean 才装
  └─ ...
```

### 举例：DataSource 自动装配

```
classpath 中有 HikariCP.class
  → @ConditionalOnClass(HikariDataSource.class) 满足
  → 容器中没有 DataSource Bean
  → @ConditionalOnMissingBean(DataSource.class) 满足
  → 自动创建 HikariDataSource
```

### 渐进式理解

> 详见独立文档：[spring-boot-auto-configuration.md](./spring-boot-auto-configuration.md)


---

## 四、Spring Boot 启动流程（7 步）

```
① main() → SpringApplication.run()
② 推断应用类型（REACTIVE / SERVLET / NONE）
③ 加载 ApplicationContextInitializer + ApplicationListener (从 spring.factories)
④ 创建 ApplicationContext
⑤ prepareContext() → 加载环境变量、注册启动类
⑥ refreshContext() → Spring 容器刷新（和 Spring 一样的流程）
⑦ afterRefresh() → 发布 ApplicationReadyEvent → 应用就绪
```

> 详细渐进式解析见独立文档：[spring-boot-startup-flow.md](./spring-boot-startup-flow.md)

---

## 五、Spring AOP 常用注解

| 注解 | 类型 | 位置 |
|------|------|------|
| `@Aspect` | 切面声明 | 类上 |
| `@Before` | 前置通知 | 方法执行前 |
| `@After` | 后置通知 | 方法执行后（不管是否异常） |
| `@AfterReturning` | 返回通知 | 正常返回后 |
| `@AfterThrowing` | 异常通知 | 抛异常后 |
| `@Around` | 环绕通知 | 前后环绕，控制最广 |
| `@Pointcut` | 切点定义 | 空方法上，复用表达式 |
| `@Order(n)` | 切面排序 | n 越小越优先 |
| `@EnableAspectJAutoProxy` | 启用 AOP | 配置类 |

```java
@Aspect
@Component
public class MyAspect {
    @Pointcut("execution(* com.axon.service.*.*(..))")
    public void pt() {}

    @Before("pt()")
    public void before() { ... }

    @Around("pt()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        // 前置
        Object result = pjp.proceed();  // 执行目标方法
        // 后置
        return result;
    }
}
```

### AOP 代理选择

| 条件 | 代理方式 |
|------|------|
| 目标有接口 | **JDK 动态代理**（基于接口） |
| 目标无接口 | **CGLIB**（基于子类继承） |
| Spring Boot 2.x 默认 | CGLIB（`proxy-target-class=true`） |

---

## 六、面试话术

### Spring 原理（30 秒版）

> **容器启动 → 扫描包解析 BeanDefinition → 反射实例化 → 依赖注入 → AOP 代理 → 发布启动事件。**
>
> **循环依赖靠三级缓存解决：** 一级存成品，二级存提前曝光的半成品，三级存产生代理的工厂。前 A 用三级暴露引用，B 注入了 A 的引用后完成初始化，A 再从一级拿 B。

### Spring Boot 自动装配（20 秒版）

> **`@SpringBootApplication` 里的 `@EnableAutoConfiguration` 导入 `AutoConfigurationImportSelector`，从 `spring.factories` 中读取全部 xxxAutoConfiguration 类。然后用 `@Conditional` 系列注解条件筛选，符合条件的自动创建 Bean。**

### Spring vs Spring Boot（15 秒版）

> Spring 手动配置，Spring Boot 自动配置 + 嵌入式服务器 + starter 起步依赖，开箱即用。

### AOP 注解（15 秒版）

> **五类通知：** Before、After、AfterReturning、AfterThrowing、Around。Around 最灵活，`proceed()` 分割前后。切面排序 `@Order`，值越小越靠前。默认 CGLIB 代理。
