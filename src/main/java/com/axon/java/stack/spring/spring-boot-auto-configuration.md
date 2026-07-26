# Spring Boot 自动装配 — 渐进式理解

> 从 `spring-summary.md` 第三部分独立出来，逐步拆解自动装配的每一步。

---

## 入口

```java
@SpringBootApplication  // 组合了 3 个注解
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## `@SpringBootApplication` 拆解

| 组成注解 | 作用 |
|------|------|
| `@Configuration` | 标记这是一个配置类 |
| `@ComponentScan` | 扫描当前包下所有 Bean |
| `@EnableAutoConfiguration` | **核心**：开启自动装配 |

---

## 渐进式理解

### 第一层：为什么你的应用"一跑就通"

传统 Spring MVC 需要手动配置 `web.xml`、`DispatcherServlet`、数据源、事务管理器……一堆 XML/注解。Spring Boot 只用写一个 `main` 方法就跑起来了。

**核心就在 `@SpringBootApplication` 这个注解**——它等于三个注解打包：

```
@SpringBootApplication  =  @Configuration + @ComponentScan + @EnableAutoConfiguration
```

---

### 第二层：三个注解逐个拆解

---

##### ① `@Configuration`：把 Java 类变成 Bean 工厂

**作用：让一个类替代原来的 XML 配置文件，专门用来定义 Bean。**

先看对比——同样配 `DataSource` + `JdbcTemplate`，XML 和 Java 两种写法：

```
【传统 Spring XML 方式】
<bean id="dataSource" class="com.zaxxer.hikari.HikariDataSource">
    <property name="url" value="jdbc:mysql://localhost:3306/test"/>
</bean>
<bean id="jdbcTemplate" class="org.springframework.jdbc.core.JdbcTemplate">
    <constructor-arg ref="dataSource"/>
</bean>

【@Configuration + @Bean 方式（等价）】
@Configuration
public class AppConfig {
    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://localhost:3306/test");
        return ds;   // 返回值变 Bean，方法名 dataSource 是 Bean 名
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);  // 参数自动从容器注入
    }
}
```

**四个关键点：**

| 要点 | 说明 |
|------|------|
| `@Bean` 方法 = 生产线 | 方法的返回值自动成为 Spring 容器管理的 Bean |
| 方法名 = Bean 名称 | `dataSource()` 产出的 Bean 就叫 `dataSource` |
| 参数自动注入 | `jdbcTemplate(DataSource ds)` — `ds` 自动从容器找到 `dataSource` |
| CGLIB 代理保障单例 | `@Configuration` 类会被 CGLIB 生成子类，多次调用同一个 `@Bean` 方法也只会返回容器里那一个实例，不会重复 `new` |

> **`@Configuration` 和 `@Component` 是什么关系？**
>
> `@Configuration` 源码内部标注了 `@Component`。也就是说它既是一个配置工厂，本身也是一个 Bean，会被 `@ComponentScan` 扫进容器，类里的所有 `@Bean` 方法产物也会自动注册。

##### ② `@ComponentScan`：把散落各地的 Bean 全部扫进来

**作用：指定一个根包路径，把下面所有标注了 Spring 注解的类自动注册为 Bean，不用一个个手写。**

**默认行为：** 以启动类（有 `@SpringBootApplication` 的类）所在的包作为根路径，向下递归扫描。

```
com.axon.demo
├── Application.java          ← @SpringBootApplication 在这里
├── service/
│   ├── UserService.java      ← @Service → 自动注册
│   └── OrderService.java     ← @Service → 自动注册
└── controller/
    └── UserController.java   ← @Controller → 自动注册
```

**它会识别这些注解的类：**

`@Component`、`@Service`、`@Repository`、`@Controller`、`@RestController`、`@Configuration` ……

> **注意：只有跟启动类同包或子包下的才会被扫到。** 如果把 `UserService` 放到 `com.other` 包下，就扫不到了，要么移动到同包路径下，要么用 `@ComponentScan("com.other")` 手动指定。

```java
// 手动指定扫描范围
@SpringBootApplication
@ComponentScan("com.axon")  // 扫这个包及所有子包
public class Application { ... }
```

##### ③ `@EnableAutoConfiguration`：凭 classpath 自动猜配

**作用：这是 Spring Boot 真正的魔法所在 — 根据项目引了哪些 jar，自动把对应的配置类激活。**

传统 Spring：你引了 HikariCP，必须手动写 `@Bean DataSource` 才能用。  
Spring Boot：你引了 `spring-boot-starter-data-jpa`，Boot 检测到 classpath 有 HikariCP → 自动给你创建 `DataSource`、`EntityManagerFactory`、`TransactionManager`……你什么都不用写。

**一句话：`@EnableAutoConfiguration` 做的事就是"看菜下饭"。**

---

### 第三层：`@EnableAutoConfiguration` 和 `AutoConfigurationImportSelector` 的角色分工

这两个东西名字很像，但职责完全不同：

| 角色 | 职责 | 类比 |
|------|------|------|
| `@EnableAutoConfiguration` | 一个**注解/开关**，声明"我要用自动装配" | 电灯开关 |
| `AutoConfigurationImportSelector` | 真正的**执行器**，读 spring.factories、汇总候选配置类名单 | 电线 + 灯泡 |

```java
@EnableAutoConfiguration
        │  @Import(AutoConfigurationImportSelector.class)  ← 开关触发执行器
        ▼
AutoConfigurationImportSelector
        │
        ├─ ① 扫描 classpath 下所有 jar 包
        ├─ ② 从每个 jar 包的 META-INF/spring.factories 读出候选配置类列表
        ├─ ③ 汇总去重 → 得到完整的"候选配置类大名单"
        └─ ④ 把这份名单交给 Spring 容器去逐个加载
```

**没有 `@EnableAutoConfiguration`，执行器不会被触发，整个自动装配流程不会启动。**

---

### 第四层：`spring.factories` — 自动装配的"通讯录"

#### 它是什么

`spring.factories` **不是存 jar 包的引用，而是存 `@Configuration` 配置类的全限定类名。** jar 包早就被 Maven/Gradle 拉到 classpath 了，spring.factories 只负责回答一个问题：

**"这个 jar 包有哪些自动配置类需要 读？"**

```
【常见误解】
spring.factories → 引用 jar 包 A → 加载 jar 包 A

【实际情况】
jar 包 A 已经在 classpath 里了（pom.xml 引入的）
  └─ jar 包 A 内部有 META-INF/spring.factories
       └─ 内容：org.xxx.DataSourceAutoConfiguration, org.xxx.WebMvcAutoConfiguration...
            → Spring Boot 去 classpath 找这些类，加载它们
```

#### 它是谁写的

**不是你写的，是每个 starter/jar 的作者写的。**

当你引入 `spring-boot-starter-web` 时，Maven/Gradle 把 `spring-boot-autoconfigure.jar` 拉进你的 classpath。这个 jar 里就有一份 `spring.factories`。

#### 它长什么样

这是 `spring-boot-autoconfigure.jar` 里真实的 `META-INF/spring.factories` 片段：

```
# 打开 spring-boot-autoconfigure 的 jar 包，找到 META-INF/spring.factories：

org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration,\
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,\
org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration,\
...(一百多个)...
```

**本质就是一个 key=值列表：**

| 字段 | 值 |
|------|------|
| key | `org.springframework.boot.autoconfigure.EnableAutoConfiguration` |
| value | 一堆 XXXAutoConfiguration 全限定类名，逗号隔开 |

#### 它的价值：可扩展的插件机制

```
spring-boot-autoconfigure.jar
  └─ META-INF/spring.factories  →  注册了 DataSourceAutoConfiguration、
                                    WebMvcAutoConfiguration 等 180+ 个类

mybatis-spring-boot-autoconfigure.jar  （第三方）
  └─ META-INF/spring.factories  →  注册了 MybatisAutoConfiguration
```

**任何 jar 包只要在它的 `META-INF/` 下放了 `spring.factories`，Spring Boot 启动时就会自动扫描并读取。** 这就是为什么你引入 `mybatis-spring-boot-starter` 后 MyBatis 就自动配好了——不用你写任何配置类，starter 自带这份"通讯录"，Boot 读到就加载。

#### 新旧版本差异

| 版本 | 文件名 | 路径 |
|------|--------|------|
| Spring Boot 2.x（老） | `spring.factories` | `META-INF/spring.factories` |
| Spring Boot 3.x（新） | `AutoConfiguration.imports` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

新版一行一个类名，更简洁：

```
# 新版 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports：
org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
```

---

### 第五层：完整链路串联

把前面所有概念串成一条完整的执行路径：

```
① Maven pom.xml 引入 starter → jar 包进入 classpath

② 启动类 @SpringBootApplication → 触发 @EnableAutoConfiguration（开关）

③ @EnableAutoConfiguration 通过 @Import 触发 AutoConfigurationImportSelector（执行器）

④ AutoConfigurationImportSelector 扫描 classpath 下所有 jar 包的 META-INF/spring.factories
   → 汇总所有 key=EnableAutoConfiguration 的配置类全限定类名
   → 得到"候选配置类大名单"

⑤ 候选名单交给 Spring 容器 → 容器逐个加载这些 @Configuration 类

⑥ @Conditional 过滤器逐项检查每个配置类的条件
   → 条件全通过：加载该类，执行 @Bean 方法 → Bean 注册进容器
   → 条件不通过：跳过该类，不注册任何 Bean
```

---

### 第六层：两条路径殊途同归 — Bean 到底从哪里来？

回顾整个流程，Spring 容器里的 Bean 其实只来自两拨：

```
                     ┌─────────────────────────────────────────────┐
                     │           Spring 容器（都注册成 Bean）         │
                     └─────────────────────────────────────────────┘
                            ▲                          ▲
                            │                          │
               ┌────────────┴──────────┐    ┌──────────┴──────────────┐
               │    @ComponentScan     │    │  @EnableAutoConfiguration │
               │                       │    │      + Selector          │
               └───────────┬───────────┘    └──────────┬──────────────┘
                           │                           │
                    扫描当前包及子包               读取所有 jar 的 spring.factories
               @Component / @Service / @Controller    加载候选 @Configuration 类
               @Repository / @RestController         → @Conditional 逐项过滤
                           │                      → 条件全通过 → 执行 @Bean 方法
                           │                           │
                           ▼                           ▼
                     你写的业务 Bean              jar 包自带的基础设施 Bean
                    (UserService, OrderService)  (DataSource, JdbcTemplate…)
```

| 路径 | 数据源 | 注册什么 | 一句话 |
|------|--------|----------|--------|
| `@ComponentScan` | 扫描**应用自己的包**，找带 `@Component` 等注解的类 | 你写的业务 Bean | **扫自己** |
| `@EnableAutoConfiguration` + Selector | 读**jar 包的 `spring.factories`**，找 `@Configuration` 类 → 执行 `@Bean` 方法 | jar 包自带的基础设施 Bean | **加载别人** |

> **两拨 Bean 殊途同归，最终都进同一个容器。**

---

### 第七层：`@Conditional` 条件过滤 — 按需激活

#### 核心原理：就是一棵 if-else 树

加载了全部候选名单，但**不是每个配置类都会创建 Bean**。每个 `@Configuration` 类上都标注了 `@Conditional` 条件注解：

```
@Configuration
@Conditional 条件1
@Conditional 条件2
public class SomeAutoConfiguration {
    @Bean  ...
    @Bean  ...
}
          │
          条件全通过？
          ├─ 是 → 配置类生效，所有 @Bean 方法执行，Bean 注册进容器
          └─ 否 → 整个配置类被忽略，一行代码都不跑，@Bean 不注册（不报错）
```

#### 常用条件注解

| 条件注解 | 含义 | 等价 if 语句 |
|----------|------|-------------|
| `@ConditionalOnClass` | classpath 有某个类 | `if (Class.forName(...) != null)` |
| `@ConditionalOnMissingClass` | classpath 没有某个类 | `if (Class.forName(...) == null)` |
| `@ConditionalOnBean` | 容器里有这个 Bean | `if (ctx.getBean(...) != null)` |
| `@ConditionalOnMissingBean` | 容器里没有这个 Bean | `if (ctx.getBean(...) == null)` |
| `@ConditionalOnProperty` | 配置文件有某属性 | `if (env.getProperty("xxx") != null)` |
| `@ConditionalOnExpression` | SpEL 表达式成立 | `if (evaluate("..."))` |

#### 真实案例：DataSourceAutoConfiguration 源码还原

```java
@Configuration
@ConditionalOnClass({DataSource.class, EmbeddedDatabaseType.class})  // ← 第一关
public class DataSourceAutoConfiguration {

    @Configuration
    @ConditionalOnMissingBean(DataSource.class)    // ← 第二关
    static class EmbeddedDatabaseConfiguration {
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()...build();  // 内嵌 H2
        }
    }

    @Configuration
    @ConditionalOnClass(HikariDataSource.class)    // ← 第三关
    @ConditionalOnMissingBean(DataSource.class)
    static class HikariPoolDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return new HikariDataSource();  // HikariCP 连接池
        }
    }
}
```

**Spring Boot 加载这个类时的判断流程：**

```
① @ConditionalOnClass(DataSource.class, EmbeddedDatabaseType.class)
   问：classpath 有这两个类吗？

   场景 A：只引了 spring-boot-starter-web，没引数据库 jar
       → classpath 没有 DataSource.class → ❌ 直接跳过整个类

   场景 B：引了 spring-boot-starter-data-jpa
       → classpath 有 DataSource.class ✅ → 继续

② EmbeddedDatabaseConfiguration：
   @ConditionalOnMissingBean(DataSource.class)
   问：容器里已经有 DataSource Bean 了吗？
       → 没有 → ✅ 创建内嵌 H2
       → 有   → ❌ 跳过

③ HikariPoolDataSourceConfiguration：
   @ConditionalOnClass(HikariDataSource.class)
   问：classpath 有 HikariCP 吗？→ 有 ✅

   @ConditionalOnMissingBean(DataSource.class)
   问：容器里已经有 DataSource Bean 了吗？
       → 第②步已创建 H2 → 有 → ❌ 跳过
       → 第②步没创建     → 没有 → ✅ 创建 HikariCP 连接池
```

#### 结论

**`@Conditional` 是仲裁者：候选名单可能有 180 个配置类，但最终生效的可能只有十几个。** 绝大多数因为条件不满足，被"静默跳过"——既不报错，也不产生任何 Bean。这就是自动装配的精髓：**大量候选、按需激活、不满足条件就自动跳过。**

---

## 面试话术

> **`@SpringBootApplication` 里的 `@EnableAutoConfiguration` 是个开关，通过 `@Import` 触发 `AutoConfigurationImportSelector` 执行器。执行器扫描所有 jar 包的 `META-INF/spring.factories`，汇总出全部 `xxxAutoConfiguration` 候选配置类。这些 `@Configuration` 类上都标注了 `@Conditional` 条件注解（`@ConditionalOnClass`、`@ConditionalOnMissingBean` 等），条件全通过才加载、执行 `@Bean` 注册；条件不满足则静默跳过。大量候选、按需激活——这就是自动装配的精髓。**
