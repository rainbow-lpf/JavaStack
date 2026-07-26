# Dubbo — 面试总结

> 目录：`com.axon.java.stack.dubbo`

---

## 一、Dubbo 核心架构

### 1.1 一句话说清 Dubbo

> **Dubbo 是高性能 Java RPC 框架，让远程服务调用像本地方法调用一样简单。**

### 1.2 核心角色

```
     ┌──────────────────────────┐
     │   Registry（注册中心）      │
     │   Zookeeper / Nacos       │
     └──────┬──────────┬────────┘
            │ 注册     │ 发现
            ▼          ▼
┌──────────────────┐   ┌──────────────────┐
│   Provider       │   │   Consumer       │
│   (服务提供者)    │   │   (服务消费者)    │
│   实际干活的      │   │   调用服务的       │
└──────────────────┘   └──────────────────┘
            │                    │
            └──────┬─────────────┘
                   ▼
          ┌──────────────┐
          │  Monitor      │
          │  (监控中心)    │
          └──────────────┘
```

| 角色 | 作用 |
|------|------|
| **Provider** | 暴露服务，实际处理业务 |
| **Consumer** | 调用远程服务 |
| **Registry** | 注册中心，存"谁提供什么服务"，Provider 注册、Consumer 订阅 |
| **Monitor** | 统计调用次数、耗时 |

---

## 二、Dubbo 调用流程（6 步）

```
① Provider 启动 → 向 Registry 注册自己的地址 + 服务接口
② Consumer 启动 → 向 Registry 订阅需要的服务
③ Registry 推送 Provider 地址列表给 Consumer
④ Consumer 通过动态代理调用 Provider（底层 Netty 网络通信）
⑤ 调用失败 → 按容错策略重试 / 换一台调
⑥ Monitor 记录调用次数、耗时
```

```java
// Consumer 使用 Dubbo，只需要：
@DubboReference
private OrderService orderService;  // 像本地方法一样调用

// 实际上：动态代理 → Netty发送RPC请求 → Provider处理 → 返回结果
Order order = orderService.getOrder(123L);
```

---

## 三、Dubbo 底层原理

### 3.1 动态代理

```
Consumer 看到的：
  @DubboReference
  OrderService orderService;
  orderService.getOrder(123);

实际发生了什么：
  orderService → 是 Dubbo 生成的代理对象
  调用 getOrder(123) → 代理拦截：
    ① 封装成 RPC 请求（接口名 + 方法名 + 参数）
    ② 序列化请求数据
    ③ Netty 发送 TCP 请求给 Provider
    ④ 等待返回
    ⑤ 反序列化结果 → 返回给你
```

### 3.2 网络通信——Netty

```
Dubbo 默认用 Netty 做底层通信：

Consumer                        Provider
  │                                │
  ├── Netty Client ──TCP──→ Netty Server
  │                                │
  ├── 发送：序列化(RPC请求)         │
  │                                ├── 接收：反序列化 → 调本地方法
  │                                │
  │                                ├── 返回：序列化(结果)
  ├── 收到：反序列化 → 给调用者     │
```

### 3.3 序列化

| 协议 | 说明 |
|------|------|
| **Hessian2**（默认） | 二进制序列化，速度快 |
| **Java Serializable** | 兼容好但大 |
| **Protobuf** | Google 的，极小 |
| **JSON** | 可读性好但慢 |

---

## 四、负载均衡策略

| 策略 | 原理 | 适用 |
|------|------|------|
| **Random（随机）** | 随机选一个，可加权 | 默认，最常用 |
| **RoundRobin（轮询）** | 按顺序轮流转 | 各节点配置相同 |
| **LeastActive（最小活跃数）** | 谁忙得最少发给谁 | 长请求不均匀 |
| **ConsistentHash（一致性哈希）** | 同参数请求固定打到同节点 | 需要粘性 |

```java
@DubboReference(loadbalance = "leastactive")
private OrderService orderService;
```

---

## 五、容错策略

| 策略 | 行为 | 适用 |
|------|------|------|
| **Failover（失败重试）** | 自动换一台重试，默认重试 2 次 | **默认** |
| **Failfast（快速失败）** | 失败直接抛异常 | 幂等操作 |
| **Failsafe（安全失败）** | 失败只记日志，不抛异常 | 日志/审计 |
| **Failback（失败恢复）** | 失败后记录，后台定时重试 | 最终一致性 |
| **Forking（并行调用）** | 同时调多个，谁快用谁 | 低延迟重要 |
| **Broadcast（广播）** | 调所有 Provider，一个失败就失败 | 通知所有节点 |

```java
@DubboReference(cluster = "failfast", retries = 0)
private OrderService orderService;
```

---

## 六、超时与重试

### 6.1 超时优先级（优先级从高到低）

|  | 优先级 | 说明 |
|------|:---:|------|
| **方法级** | 🥇 最高 | `@DubboReference(timeout=2000, methods={@Method(name="getOrder", timeout=1000)})` |
| **接口级** | 🥈 | `@DubboReference(timeout=3000)` |
| **Provider 端** | 🥉 | `@DubboService(timeout=5000)` |
| **全局配置** | 最低 | `dubbo.provider.timeout=5000` |

### 6.2 重试次数

```java
@DubboReference(retries = 2)  // 失败后重试 2 次（共调 3 次）
private OrderService orderService;
```

> **注意：** 重试只适用于**幂等**操作。写操作（扣库存、支付）不要设重试，用 Failfast。

---

## 七、Zookeeper 宕机后 Dubbo 还能用吗？

```
✅ 可以！
Consumer 第一次订阅时会把 Provider 地址缓存到本地。
Zookeeper 宕机 → 新 Provider 无法注册，新 Consumer 无法发现
但已运行的 Consumer 仍能用本地缓存的地址调用 Provider。
```

```
    正常时                          ZK 挂了后
   Registry                         Registry ❌
   ↑       ↑                       /        \
 注册      发现               Provider    Consumer
   │        │                    ↑           │
 Provider  Consumer               │   用本地缓存调  │
                                  └─────────────┘
                                       还能通
```

---

## 八、Dubbo 常见面试题

### Q1：Dubbo 调用过程是怎样的？（流程图回答）

> Provider 注册到 Registry → Consumer 从 Registry 发现服务 → Consumer 动态代理发起调用 → Netty TCP 传输 → Provider 处理并返回 → 结果反序列化返回给 Consumer。

### Q2：Dubbo 支持哪些协议？各有什么特点？

| 协议 | 特点 | 适用 |
|------|------|------|
| **dubbo**（默认） | 单一长连接，NIO 异步，小数据量大并发 | 默认 |
| **rmi** | JDK 自带 | 纯 Java |
| **hessian** | HTTP 短连接 | 跨语言 |
| **http** | JSON + REST | 前后端 |
| **rest** | 标准 RESTful | 对外 API |

### Q3：Dubbo 的负载均衡策略有哪些？

> Random（随机加权，默认）、RoundRobin（轮询）、LeastActive（最少并发）、ConsistentHash（一致性哈希）。

### Q4：Dubbo 的容错机制有哪些？

> Failover（失败自动切换重试，默认）、Failfast（快速失败）、Failsafe（安全失败）、Failback（失败后台恢复）、Forking（并行调用）、Broadcast（广播）。

### Q5：Dubbo 怎么保证服务高可用？

> ① 多 Provider 部署，挂了自动剔除；② Consumer 本地缓存地址列表，ZK 宕机也不影响调用；③ 容错策略自动切换；④ 动态代理透明化。

### Q6：RPC 调用超时怎么排查？

> ① 看 Provider 日志有没有异常；② 检查线程池是否打满；③ 检查数据库/Redis 是否有慢查询；④ 网络延迟；⑤ Dubbo 超时设置太小。

### Q7：Dubbo 和 Spring Cloud 选哪个？

| 选 Dubbo | 选 Spring Cloud |
|------|------|
| 纯服务间高性能调用 | 需要全套微服务治理 |
| 对延迟敏感 | 需要 API 网关、配置中心、链路追踪 |
| 阿里系技术栈 | Spring 全家桶 |

### Q8：Dubbo 怎么实现服务分组和版本控制？

```java
// 版本
@DubboService(version = "1.0.0")  // Provider
@DubboReference(version = "1.0.0")  // Consumer

// 分组
@DubboService(group = "prod")
@DubboReference(group = "prod")
```

### Q9：Java SPI 和 Dubbo SPI 有什么区别？

> **SPI = Service Provider Interface，插件化机制。** 定义接口，让别人实现，运行时动态加载。

| | Java SPI | Dubbo SPI |
|------|------|------|
| **配置文件** | `META-INF/services/接口名` | `META-INF/dubbo/接口名` |
| **加载方式** | **一次性加载所有实现类** | **按需加载，用 key 指定实现类** |
| **K/V 配对** | ❌ 只有全类名 | ✅ `key=全类名`，按 key 获取 |
| **AOP 增强** | ❌ | ✅ Wrapper 自动包装 |
| **IOC 注入** | ❌ | ✅ setter 自动注入 |

**Java SPI 示例：**

```java
// META-INF/services/com.axon.OrderService 内容：
// com.axon.AliPayOrderService
// com.axon.WechatPayOrderService

ServiceLoader<OrderService> loader = ServiceLoader.load(OrderService.class);
for (OrderService s : loader) {
    s.pay();  // 所有实现类全部加载，即使你只要一个
}
```

**Dubbo SPI 示例：**

```java
// META-INF/dubbo/com.axon.OrderService 内容：
// alipay=com.axon.AliPayOrderService
// wechat=com.axon.WechatPayOrderService

ExtensionLoader<OrderService> loader = ExtensionLoader.getExtensionLoader(OrderService.class);
OrderService service = loader.getExtension("wechat");  // 只加载 WechatPayOrderService！
service.pay();
```

**Dubbo SPI 的 AOP 包装：**

```
Dubbo 调用链通过 Wrapper 一层一层包裹：
[超时Filter] → [监控Filter] → [异常Filter] → [业务实现]
── Wrapper（别名装饰器模式），不改业务代码自动插 Filter。
```

**一句话：** Java SPI 一把梭全加载，Dubbo SPI 按 key 按需加载 + AOP 包装 + IOC 注入。Dubbo 的 Filter、协议、负载均衡切换全靠这套 SPI。**

---

## 九、面试话术

### Dubbo 核心（30 秒版）

> Dubbo 是高性能 Java RPC 框架，核心四角色：Provider 暴露服务、Consumer 调服务、Registry 注册发现、Monitor 监控。Consumer 通过动态代理发起调用，底层 Netty TCP 传输。支持多协议、多负载均衡、多容错策略。ZK 宕机后本地缓存仍可用。
