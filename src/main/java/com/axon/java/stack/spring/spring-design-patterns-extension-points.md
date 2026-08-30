# Spring 设计模式与扩展点

> 面试三连：源码里用了哪些设计模式？扩展点有哪些？项目中真实扩展过什么？

---

## 一、设计模式清单（源码位置版）

| 模式 | Spring 中的位置 | 一句话说明 |
|------|----------------|-----------|
| **工厂模式** | `BeanFactory` / `DefaultListableBeanFactory` | Bean 容器本体就是大工厂 |
| **工厂的工厂** | `FactoryBean#getObject()` | 定制复杂对象创建，`&` 前缀取工厂本身 |
| **单例（注册表式）** | `DefaultSingletonBeanRegistry` 的 `singletonObjects` | 一级缓存 Map，非私有构造那种单例 |
| **原型模式** | `scope="prototype"` | 每次获取新实例 |
| **模板方法** | `AbstractApplicationContext#refresh()`；`JdbcTemplate` / `RedisTemplate` / `RestTemplate` | refresh 定义启动骨架，子类钩子（`onRefresh()`） |
| **代理模式** | AOP：`JdkDynamicAopProxy`（接口）/ `CglibAopProxy`（子类） | AOP 的核心实现 |
| **策略模式** | `InstantiationStrategy`（实例化策略）；`AopProxy`（JDK vs CGLIB 两策略） | 同一接口不同实现可切换 |
| **观察者模式** | `ApplicationEvent` + `ApplicationListener` + `ApplicationEventMulticaster` | 事件驱动解耦 |
| **责任链模式** | MVC `HandlerExecutionChain`（拦截器链）；AOP `ReflectiveMethodInvocation#proceed()` 递归链 | 多个处理器依次过 |
| **适配器模式** | `AdvisorAdapter`（通知→拦截器）；MVC `HandlerAdapter` | 让不同类型处理器统一接入 |
| **委派模式** | `DispatcherServlet` 委派给 `HandlerAdapter`；`BeanDefinitionParserDelegate` | 前端控制器只做分发 |
| **建造者模式** | `BeanDefinitionBuilder`；`UriComponentsBuilder` | 链式组装复杂对象 |
| **组合模式** | 动态 SQL 不在 Spring，见 MyBatis `SqlNode` 树 | 树形结构统一处理 |

### 重点展开 3 个（面试官会追问细节）

#### 1. 代理模式——AOP 的两条腿

```
目标类有接口   → JdkDynamicAopProxy    ：实现 InvocationHandler，运行时生成 $Proxy 类
目标类无接口   → CglibAopProxy          ：字节码生成子类，方法拦截
选择逻辑      → DefaultAopProxyFactory#createAopProxy 按条件分派（策略+工厂）
```

#### 2. 责任链——AOP 拦截器链的递归执行

```java
// ReflectiveMethodInvocation#proceed() 简化
public Object proceed() throws Throwable {
    if (index == interceptors.size()) return invokeJoinpoint();  // 链尾→目标方法
    Object interceptor = interceptors.get(index++);
    return ((MethodInterceptor) interceptor).invoke(this);       // 传 this → 每个拦截器
}                                                                // 调 proceed() 驱动链条
```

`@Transactional`（TransactionInterceptor）和你自定义的切面就在这条链上按 `@Order` 排队。

#### 3. 模板方法——refresh() 的启动骨架

```
refresh() 13 步：prepareRefresh → obtainFreshBeanFactory（加载 BeanDefinition）
  → invokeBeanFactoryPostProcessors → registerBeanPostProcessors
  → initMessageSource → initApplicationEventMulticaster → onRefresh()【钩子】
  → registerListeners → finishBeanFactoryInitialization（实例化非懒加载单例）
  → finishRefresh（发布 ContextRefreshedEvent）
```

---

## 二、扩展点全景（按生命周期排序）

```
容器启动
  ├─ ① BeanFactoryPostProcessor          （改 BeanDefinition，实例化之前）
  │     └─ BeanDefinitionRegistryPostProcessor（还能注册新 BeanDefinition）
  ├─ ② 实例化 Bean
  │     ├─ InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
  │     ├─ 构造器
  │     ├─ postProcessAfterInstantiation
  │     ├─ postProcessProperties          ← @Autowired/@Value 在这注入
  │     ├─ Aware 回调（BeanNameAware / ApplicationContextAware …）
  │     ├─ BeanPostProcessor#postProcessBeforeInitialization ← @PostConstruct 在这
  │     ├─ InitializingBean#afterPropertiesSet → init-method
  │     └─ BeanPostProcessor#postProcessAfterInitialization ← ★AOP 代理在这生成
  ├─ ③ SmartInitializingSingleton         （所有单例就绪后）
  ├─ ④ ContextRefreshedEvent              （容器刷新完成）
  └─ ⑤ ApplicationReadyEvent（Boot）      （完全启动就绪）
```

### 高频扩展点 + 框架实例

| 扩展点 | 干什么 | 框架源码实例 |
|--------|--------|-------------|
| `BeanFactoryPostProcessor` | 改 Bean 定义（占位符替换） | `PropertySourcesPlaceholderConfigurer` |
| `BeanDefinitionRegistryPostProcessor` | 动态注册 BeanDefinition | `ConfigurationClassPostProcessor`（把 @Configuration/@Bean 解析成 BeanDefinition 的就是它） |
| `BeanPostProcessor#AfterInitialization` | 包一层代理 | `AbstractAutoProxyCreator`（AOP 代理在此生成） |
| `InstantiationAwareBeanPostProcessor` | 属性注入 | `AutowiredAnnotationBeanPostProcessor`（@Autowired） |
| `FactoryBean` | 定制复杂对象创建 | `SqlSessionFactoryBean`、`FeignClientFactoryBean` |
| `ImportSelector` / `ImportBeanDefinitionRegistrar` | @EnableXXX 开关注册组件 | `@MapperScan`→`MapperScannerRegistrar`、`@EnableFeignClients`、`@EnableAspectJAutoProxy` |
| `ApplicationListener` | 事件监听 | `ContextRefreshedEvent` 做启动后动作 |
| `SmartLifecycle` | 启停生命周期，带 phase 排序 | MQ 消费者启动/停止 |
| `HandlerInterceptor` | MVC 请求拦截 | 登录态校验、TraceId 透传 |

---

## 三、项目中的真实扩展场景（举 3 个，代码级）

### 场景 1：@ApplicationReadyEvent 做活动预热（最贴业务）

活动大促前，应用启动完成即把活动配置、库存预热进 Redis + 本地缓存：

```java
@Component
public class ActivityWarmUpRunner implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        CompletableFuture.runAsync(() -> activityService.warmUp());  // 异步不阻塞
    }
}
```

**为什么不用 @PostConstruct：** 那时容器还没就绪，依赖可能没注入完；ReadyEvent 保证全链路就绪。

### 场景 2：ImportBeanDefinitionRegistrar + 自定义注解，做公司内部 Starter

自研 `@EnableDistributedIdempotent`（幂等组件）——类路径上自动注册幂等切面 + Redis 模板：

```java
public class IdempotentRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition("idempotentAspect",
            BeanDefinitionBuilder.genericBeanDefinition(IdempotentAspect.class).getBeanDefinition());
    }
}

@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
@Import(IdempotentRegistrar.class)
public @interface EnableDistributedIdempotent { }
```

业务方引 jar + `@EnableDistributedIdempotent` 一行注解即接入——**和 @MapperScan、@EnableFeignClients 同一原理**。

### 场景 3：BeanFactoryPostProcessor 实现环境隔离开关

灰度环境把某些 Bean 换成降级实现（不改业务代码）：

```java
@Component
public class EnvSwitchPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        if ("gray".equals(env)) {
            BeanDefinition bd = bf.getBeanDefinition("payClient");
            bd.setBeanClassName("GrayPayClient");   // 直接替换实现类
        }
    }
}
```

### 场景 4（备选）：BeanPostProcessor 统一给标注 @Metric 的 Bean 包统计代理

思路复刻 `AbstractAutoProxyCreator`：`postProcessAfterInitialization` 里判断注解 → `Proxy.newProxyInstance` 包一层耗时统计。

---

## 四、面试总结话术

> Spring 里的设计模式是一套组合拳：容器本体是工厂（BeanFactory），Bean 默认注册表式单例，AOP 是代理模式（接口走 JDK、无接口走 CGLIB，两个策略由工厂分派），refresh() 是模板方法定义启动骨架，事件机制是观察者，拦截器链和 AOP 的 proceed() 是责任链，DispatcherServlet 是委派。扩展点我按生命周期背：实例化前 BFPP 改定义（ConfigurationClassPostProcessor 就在这解析 @Configuration），属性填充靠 InstantiationAwareBeanPostProcessor（@Autowired 在这），初始化后 BeanPostProcessor 包代理（AOP 在这），最后事件、Runner、SmartLifecycle 收尾。真实项目里我用过三样：ReadyEvent 做活动预热、ImportBeanDefinitionRegistrar 做幂等 Starter（和 @MapperScan 同原理）、BFPP 做环境级 Bean 替换。

---

## 五、考前速记卡

### 必背 10 条（⭐⭐⭐）

1. 容器本体 = **工厂**（`BeanFactory`）；`FactoryBean` 是"工厂的工厂"，产物才是真 Bean
2. Bean 默认单例 = **注册表式单例**（`singletonObjects` 一级缓存），不是私有构造单例
3. AOP = **代理**：有接口 JDK 动态代理 / 无接口 CGLIB，`DefaultAopProxyFactory` 分派（策略+工厂）
4. **AOP 代理在哪生成**：`BeanPostProcessor.postProcessAfterInitialization`（`AbstractAutoProxyCreator`）
5. **@Autowired 在哪注入**：`InstantiationAwareBeanPostProcessor.postProcessProperties`（`AutowiredAnnotationBeanPostProcessor`）
6. `refresh()` = **模板方法**：13 步骨架 + `onRefresh()` 钩子
7. **责任链**：`ReflectiveMethodInvocation.proceed()` 递归，`@Transactional` 与自定义切面按 `@Order` 排队
8. 事件机制 = **观察者**：`ContextRefreshedEvent` / `ApplicationReadyEvent`
9. 动态注册 Bean 三剑客：`@Import` → `ImportSelector` / `ImportBeanDefinitionRegistrar`（`@MapperScan` 原理）
10. 改 Bean 定义在实例化前：`BeanFactoryPostProcessor`（占位符替换）；`@Configuration` 解析靠 `ConfigurationClassPostProcessor`

### 追问预判（面试官下一句 → 一句话回）

| 追问 | 一句话答案 |
|------|-----------|
| FactoryBean 和 BeanFactory 区别？ | FactoryBean 本身是 Bean，`getObject()` 产物才是真 Bean；`&name` 取工厂本身 |
| @PostConstruct 和 afterPropertiesSet 谁先？ | @PostConstruct（BeanPostProcessor 机制）先于 afterPropertiesSet |
| 代理什么时候包上去？ | Bean 初始化完成后，postProcessAfterInitialization 阶段 |
| @PostConstruct 为什么不够用？ | 那时依赖/容器可能没就绪，预热要用 ReadyEvent |
| 循环依赖怎么解？ | 三级缓存：singletonObjects / earlySingletonObjects / singletonFactories（早期引用） |

### 记忆权重

- ⭐⭐⭐ 必背：1~8 条（都是面试高频）
- ⭐⭐ 理解：9、10（能和 MyBatis/Dubbo 串起来更佳）
- ⭐ 了解：循环依赖三级缓存细节
