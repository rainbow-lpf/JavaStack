# Spring Boot 启动流程 — 渐进式理解

> 从 `spring-summary.md` 第四部分独立出来，逐步拆解 Spring Boot 启动的 7 个步骤。

---

## 入口

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);  // 一切从这里开始
    }
}
```

---

## 七步总览

```
① main() → SpringApplication.run()
② 推断应用类型（REACTIVE / SERVLET / NONE）
③ 加载 ApplicationContextInitializer + ApplicationListener (从 spring.factories)
④ 创建 ApplicationContext
⑤ prepareContext() → 加载环境变量、注册启动类
⑥ refreshContext() → Spring 容器刷新（和 Spring 一样的流程）
⑦ afterRefresh() → 发布 ApplicationReadyEvent → 应用就绪
```

---

## 渐进式理解

### 第一层：`SpringApplication.run()` 到底干了什么

你写了一行 `SpringApplication.run(Application.class, args)`，它背后是一个**精心编排的启动流水线**，每一步都有明确的职责：

| 步骤 | 核心问题 |
|------|----------|
| ② | 这是 Web 应用还是命令行工具？ |
| ③ | 有什么扩展点需要提前准备？ |
| ④ | 该用哪一种容器？ |
| ⑤ | 环境配置好了吗？ |
| ⑥ | 所有 Bean 都创建好了吗？ |
| ⑦ | 一切就绪，可以干活了吗？ |

**类比：盖一栋楼** — 先看地块类型（②）→ 拉施工队和设备（③）→ 打地基选结构（④）→ 通水通电（⑤）→ 主体施工（⑥）→ 验收交房（⑦）。

---

### 第二层：逐步拆解

---

#### ① `main()` → `SpringApplication.run()`

这是入口行。`run()` 方法内部做了两件事：

```java
// SpringApplication.run() 的简化版内部逻辑：

public static ConfigurableApplicationContext run(Class<?> primarySource, String[] args) {
    // 1. new 一个 SpringApplication 对象（做第②步的推断）
    SpringApplication app = new SpringApplication(primarySource);
    
    // 2. 执行 run（完成 ③~⑦）
    return app.run(args);
}
```

**`new SpringApplication()` 阶段就做了第②步的推断。**

---

#### ② 推断应用类型

Spring Boot 要回答一个问题：**你是 Web 应用还是普通命令行程序？**

```
SpringApplication 构造器：
    ↓
检查 classpath 是否存在以下类：
    ↓
┌─────────────────────────────────────────────────────┐
│  存在 DispatcherHandler.class（Reactive Web）        │
│  → 类型 = REACTIVE                                  │
│                                                     │
│  不存在 DispatcherHandler，但存在 Servlet.class       │
│  → 类型 = SERVLET                                   │
│                                                     │
│  以上两个都不存在                                    │
│  → 类型 = NONE（普通命令行程序，不启动 Web 服务器）    │
└─────────────────────────────────────────────────────┘
```

| 推断结果 | 含义 | 用什么容器 |
|----------|------|-----------|
| `SERVLET` | 传统 Servlet Web 应用 | `AnnotationConfigServletWebServerApplicationContext` |
| `REACTIVE` | 响应式 Web 应用 | `AnnotationConfigReactiveWebServerApplicationContext` |
| `NONE` | 非 Web 应用 | `AnnotationConfigApplicationContext` |

> **为什么需要推断？** 不同类型用的 `ApplicationContext` 不一样。推断结果直接影响第④步创建哪个容器。

---

#### ③ 加载 `ApplicationContextInitializer` 和 `ApplicationListener`

这一步是 Spring Boot 的**扩展点机制**。启动过程中允许外部代码介入，但不是靠你手动注册，而是**从 `spring.factories` 自动读出来**。

```
Spring Boot 扫描 classpath 下所有 jar 的 META-INF/spring.factories
    ↓
按 key 读出不同的扩展点：
    ├─ key=ApplicationContextInitializer   → "容器初始化前的回调"
    ├─ key=ApplicationListener             → "事件监听器"
    ├─ key=EnableAutoConfiguration         → 自动配置类（在第⑥步加载）
    └─ key=FailureAnalyzer                 → 启动失败分析器
```

> **不是每个 jar 都注册了这些。**
>
> `spring.factories` 是一张多用途登记表，每个 jar 只填它需要的那一栏：
> - Spring Boot 自己的 jar → 四栏都注册
> - MyBatis starter → 只注册 `EnableAutoConfiguration`（自动配置类）
> - 普通业务 jar → 大概率一份 `spring.factories` 都没有
> - Spring Cloud → 大量注册 `ApplicationListener`（监听配置刷新、服务注册等）

**它们各自做什么？**

| 扩展点 | 时机 | 典型用途 |
|--------|------|----------|
| `ApplicationContextInitializer` | **容器刷新之前**，最早介入 | 激活 profile、设置环境变量 |
| `ApplicationListener` | 启动过程中**各阶段事件**发布时回调 | 监听启动进度、做初始化日志 |

**类比：** `Initializer` 是开工前调整施工方案的顾问；`Listener` 是每个里程碑拍照记录的监理。

> **这也是从 `spring.factories` 读的，跟自动装配读配置类是同一份文件、同一个机制。**

---

#### ④ 创建 `ApplicationContext`

根据第②步推断的应用类型，创建对应的容器：

```
推断类型 = SERVLET  →  new AnnotationConfigServletWebServerApplicationContext()
推断类型 = REACTIVE →  new AnnotationConfigReactiveWebServerApplicationContext()
推断类型 = NONE     →  new AnnotationConfigApplicationContext()
```

这个容器对象创建出来后还是空的，接下来的步骤往里装东西。

---

#### ⑤ `prepareContext()`：准备上下文

**这一步是"装修前的准备工作"——把环境变量、启动类、Initializer 都就位。**

```
prepareContext() 内部做的事：

1. 把环境变量（application.yml/application.properties）绑定到容器
2. 把启动类（Application.class）注册为 BeanDefinition 的源头
3. 执行所有 ApplicationContextInitializer
   → 给你最后一次机会在容器刷新前做一些定制
4. 注册一个特殊的 Bean：SpringApplication 的启动参数
```

**关键点：`prepareContext()` 只是准备，还没开始创建 Bean。真正的 Bean 创建在第⑥步。**

---

#### ⑥ `refreshContext()`：Spring 容器刷新

**这是整个启动流程中最重的一步。** 它调用的是标准的 Spring `AbstractApplicationContext.refresh()` 方法，和纯 Spring 项目一模一样的流程。自动装配也发生在这里。

```
refresh() 内部 12 个关键步骤：

① prepareRefresh()               → 准备刷新，记录启动时间
② obtainFreshBeanFactory()       → 创建 BeanFactory
③ prepareBeanFactory()           → 给 BeanFactory 设置 ClassLoader、表达式解析器等
④ postProcessBeanFactory()       → BeanFactory 后置处理（留给子类扩展）
⑤ invokeBeanFactoryPostProcessors()
   ├─ 执行 BeanDefinitionRegistryPostProcessor
   └─ 执行 BeanFactoryPostProcessor
   ★★★ AutoConfigurationImportSelector 在这里被触发！→ 读 spring.factories → 加载候选配置类 ★★★
⑥ registerBeanPostProcessors()   → 注册 BeanPostProcessor
⑦ initMessageSource()            → 初始化国际化资源
⑧ initApplicationEventMulticaster() → 初始化事件广播器
⑨ onRefresh()                    → 留给子类，Web 容器在这创建内嵌 Tomcat
⑩ registerListeners()            → 注册事件监听器
⑪ finishBeanFactoryInitialization()
   └─ ★★★ 所有单例 Bean（非懒加载）在这里完成实例化、依赖注入、初始化 ★★★
⑫ finishRefresh()
   └─ 发布 ContextRefreshedEvent
```

**第⑤步触发自动装配，第⑪步完成所有 Bean 的创建。** 这就是前面"自动装配"文章讲的内容发生的时机。

---

#### ⑦ `afterRefresh()`：应用就绪

容器刷新完毕后，最后一步——发布一个事件，告诉所有监听器："一切就绪，可以开工了"。

```
afterRefresh() 内部：

发布 ApplicationReadyEvent
    │
    ▼
所有监听了这个事件的 ApplicationListener 被回调
    ├─ 打印 "Started Application in X.XXX seconds"
    ├─ 你的 @EventListener 或 CommandLineRunner 在这里执行
    └─ 外部监控系统收到"应用已启动"信号
```

**这就是你看到控制台输出 `Started Application in 3.456 seconds` 的地方。**

---

### 第三层：完整时序图

```
main()
  │
  ▼
SpringApplication.run()
  │
  ├─ ② 推断类型 → SERVLET / REACTIVE / NONE
  │
  ├─ ③ 从 spring.factories 加载 Initializer + Listener
  │
  ├─ ④ 创建 ApplicationContext（根据推断的类型）
  │
  ├─ ⑤ prepareContext()
  │     ├─ 绑定 application.yml
  │     ├─ 注册启动类
  │     └─ 执行 Initializer
  │
  ├─ ⑥ refreshContext()  ← 最重的一步
  │     ├─ BeanFactoryPostProcessor 执行
  │     │    └─ ★ AutoConfigurationImportSelector 触发 → 自动装配
  │     ├─ 创建内嵌 Tomcat（Web 应用）
  │     └─ ★ 所有单例 Bean 实例化、依赖注入、AOP 代理
  │
  └─ ⑦ afterRefresh()
        └─ 发布 ApplicationReadyEvent → "Started in X seconds"
```

---

### 第四层：关键问题答疑

#### Q1：自动装配到底在启动流程的哪一步发生？

**在第⑥步 `refreshContext()` → `invokeBeanFactoryPostProcessors()`。**

`AutoConfigurationImportSelector` 本质上是一个 `BeanFactoryPostProcessor`，在 Step ⑤ 被触发。它读 `spring.factories`，把候选配置类注册为 `BeanDefinition`。然后 Step ⑪ 真正创建 Bean。

#### Q2：`prepareContext()` 和 `refreshContext()` 有什么区别？

| 方法 | 阶段 | 做什么 | 类比 |
|------|------|--------|------|
| `prepareContext()` | 准备期 | 绑环境变量、注册启动类、执行 Initializer | 通水通电 |
| `refreshContext()` | 施工期 | 创建所有 Bean、触发自动装配、启动 Web 服务器 | 主体施工 |

#### Q3：`ApplicationContextInitializer` 和 `BeanFactoryPostProcessor` 有什么区别？

| 扩展点 | 时机 | 可见范围 |
|--------|------|----------|
| `ApplicationContextInitializer` | `prepareContext()` 阶段，容器还没刷新 | 整个 ApplicationContext |
| `BeanFactoryPostProcessor` | `refresh()` 阶段，BeanFactory 已创建 | BeanFactory 的 BeanDefinition |

#### Q4：第⑨步 `onRefresh()` 为什么能创建内嵌 Tomcat？

当应用类型是 SERVLET 时，`ApplicationContext` 用的是 `ServletWebServerApplicationContext`，它的 `onRefresh()` 方法被重写了——**在这里创建并启动内嵌 Tomcat/Jetty/Undertow**。纯 Spring 项目的 `onRefresh()` 是空的。

---

## 面试话术

> **`SpringApplication.run()` 启动分七步：推断应用类型（Servlet/Reactive）→ 从 `spring.factories` 加载 Initializer 和 Listener → 创建对应 ApplicationContext → `prepareContext()` 准备环境和启动类 → `refreshContext()` 刷新容器（自动装配 + 创建所有 Bean + 启动内嵌 Tomcat 全在这一步）→ `afterRefresh()` 发布 `ApplicationReadyEvent`，输出 `Started` 日志。最重的是第⑥步 `refresh()`，它是纯 Spring 的标准流程，自动装配在 `invokeBeanFactoryPostProcessors` 阶段触发。**
