# 链路追踪（Sleuth / Micrometer Tracing）

> 目录：`com.axon.java.stack.springcloud`

---

## 一、一句话说清楚

> 微服务调用链太长（A → B → C → D），出问题不知道卡在哪个环节。链路追踪给每个请求一个全局唯一 traceId，贯穿整条调用链，哪个节点慢、哪个节点报错，一目了然。

---

## 二、没有追踪的痛点

```
用户投诉：下单页面打开很慢

排查过程：
  1. 查 Order-Service 日志 → 正常（等待耗时）
  2. 查 Inventory-Service 日志 → 正常（等待耗时）
  3. 查 User-Service 日志 → 错误：DB 超时

  → 从 20 个实例的 500MB 日志里人肉搜 30 分钟

有链路追踪：

  一个 traceId 串起完整调用链：
  Gateway(traceId=abc) → Order(traceId=abc) → User(traceId=abc) → DB
  
  直接按 traceId 搜，2 分钟定位到 User-Service 的 DB 慢查询
```

---

## 三、核心概念

### 3.1 Trace 和 Span

```
一个请求的完整链路 = 一个 Trace
  ├─ Span: Gateway 处理              spend=5ms
  ├─ Span: Order-Service 处理        spend=50ms
  │    ├─ Span: 查库存               spend=10ms
  │    └─ Span: 查用户               spend=200ms   ← 异常！慢
  │         └─ Span: DB              spend=180ms   ← 根因
  └─ Span: 返回响应

Trace：一个完整的请求从进到出
Span：链路中的一个操作单元（一段处理）
```

### 3.2 traceId 和 spanId

| ID | 含义 | 示例 |
|----|------|------|
| **traceId** | 全局唯一，一条链路全程不变 | `5f7a8b9c1234abcd` |
| **spanId** | 每个 Span 唯一 | `abcd1111`, `efgh2222` |
| **parentSpanId** | 当前 Span 的父 Span ID | 只有根 Span 为空 |

---

## 四、Sleuth / Micrometer Tracing 原理

### 4.1 传递流程

```
Gateway（入口）
   │ 生成 traceId + spanId
   │
   ▼
Order-Service
   │ 带入上游的 traceId（HTTP Header: X-B3-TraceId）
   │ 生成自己的 spanId，记录 parentSpanId
   │
   ▼
User-Service
   │ 继续带上游 traceId
   │ 生成自己的 spanId
   │
   ▼
  DB（结束）
```

### 4.2 自动注入机制

Spring Cloud Sleuth（新版 Micrometer Tracing）通过 AOP 拦截器自动：

```
Feign 请求 → 往请求头塞当前 traceId（X-B3-TraceId）
Feign 被调用 → 从请求头取上游 traceId → 串上
RestTemplate → 同上
消息队列 → 往 Message Header 里塞 traceId
```

开发者**代码 0 行修改**，完全自动织入，只需要引入依赖。

### 4.3 依赖（新版）

```xml
<!-- 旧版：Spring Cloud Sleuth -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>

<!-- 新版：Micrometer Tracing（Spring Boot 3.x / 推荐） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
```

---

## 五、接入 Zipkin / SkyWalking

### 5.1 Zipkin（轻量，适合中小项目）

```
日志采样 → HTTP → 集中收集器 → Zipkin UI

  Service-A ──→ Zipkin Server ──→ 可视化 UI
  Service-B ──┘
  Service-C ──┘

配置：
spring:
  zipkin:
    base-url: http://zipkin-server:9411
    sender:
      type: web               # HTTP 方式发送
  sleuth:
    sampler:
      probability: 1.0         # 采样率 100%（生产建议 0.1，减少开销）
```

### 5.2 Zipkin 持久化（防止重启丢失）

```
默认：Zipkin 存内存，重启丢
生产：接 ElasticSearch / MySQL

存储选项：
  - 内存（默认）：开发用
  - MySQL：小规模
  - ElasticSearch：大规模，推荐
```

### 5.3 SkyWalking（重武器，大厂方案）

```
Service-A → SkyWalking Agent（字节码插桩）
Service-B → SkyWalking Agent（字节码插桩）
     │
     ▼
SkyWalking OAP Server（接收端 + 分析引擎）
     │
     ▼
SkyWalking UI（可视化大盘）

特点：
  - 无代码侵入（Java Agent 字节码增强）
  - 内支撑 RocketMQ / Kafka 多种探针上报
  - 拓扑图、调用链、JVM 监控一整套
  - 性能开销高于 Zipkin，功能也更全
```

---

## 六、采样策略

| 采样率 | 说明 | 适用场景 |
|--------|------|---------|
| 100%（1.0） | 所有请求都收集 | 开发/测试 |
| 10%～20%（0.1） | 采样 10% | 中等流量生产 |
| 自定义 | 错误 100% 收集 + 正常 10% 采样 | 兼顾问题排查和性能开销 |

---

## 七、关键技术点

### 7.1 跨线程传递

```
默认 ThreadLocal 不能跨线程传 traceId。

@Async 新线程 / 线程池 → traceId 丢失

解法：
  - Sleuth 自带 LazyTraceExecutor，包装线程池即可自动传递
  - 自定义：TraceRunnable / TraceCallable
```

### 7.2 消息队列传递

```
Order-Service 发消息 → User-Service 消费

需要：
  - 发送端往 Message Header 塞 traceId
  - 消费端从 Message Header 取 traceId

Sleuth 已自动对 RabbitMQ / Kafka 织入，开箱即用
```

### 7.3 跨微服务唯一请求标识

```
日志里需要出现 traceId：

logback.xml 加：
  %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}/%X{spanId}] %-5level %logger{50} - %msg%n

效果：
  2024-07-07 14:30:01.234 [http-nio-8080] [abc123/def456] INFO  c.a.s.UserService - 查询用户
```

---

## 八、Sleuth vs Micrometer Tracing

| 维度 | Sleuth（旧） | Micrometer Tracing（新） |
|------|-----------|----------------------|
| 适用范围 | Spring Boot 2.x | Spring Boot 3.x（Sleuth 停更） |
| 底层实现 | Brave API 直接调用 | Micrometer Observation API（抽象层） |
| 接入门槛 | 引入依赖即可 | 同，Api 保持一致 |
| 监控 | 自闭环 | 与 Micrometer 指标体系打通（Metrics + Tracing 一条线） |

---

## 九、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| traceId 在调用链中断了 | 中间某个服务没接 Sleuth | 所有微服务统一加依赖 |
| traceId 跨线程丢失 | 线程池没包装 | 用 LazyTraceExecutor |
| Zipkin 搜不到某条链路 | 被采样漏掉 / 延迟上报 | 调采样率 1.0 测，查 Zipkin 服务 HTTP 探活 |
| Zipkin 重启后历史链路丢失 | 默认存内存 | 接 ElasticSearch 持久化 |
| SkyWalking Agent 对应用有性能压力 | 字节码插桩有额外开销 | 降采样率、调大 Agent 缓冲区 |

---

## 十、面试话术（30 秒版）

> 链路追踪给每个请求一个全局唯一 traceId，贯穿所有微服务调用链。底层通过 AOP 自动在请求头里透传 traceId 和 spanId，代码无侵入。轻量用 Zipkin，需要 JVM 监控和拓扑分析用 SkyWalking，Java Agent 字节码增强接入。生产采样率 5~10% 即可，跨线程传递需包装线程池。
