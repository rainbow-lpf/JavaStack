# Feign 与 Dubbo 调用链路对比

## 一、一句话说清楚

> Feign 本质是声明式 HTTP 客户端，常见链路是 Java 方法调用转成 HTTP + JSON 请求，再进入对方的 Controller。

> Dubbo 本质是 RPC 框架，常见链路是 Java 方法调用转成 RPC 请求，通过 Netty 长连接发送到 Provider，再直接调用对方的 Service 实现类。

所以二者不是同一层面的东西：

| 对比项 | Feign | Dubbo |
|------|------|------|
| 核心定位 | 声明式 HTTP 客户端 | RPC 框架 / 微服务治理框架 |
| 常见协议 | HTTP/1.1 + JSON | Dubbo2 TCP 二进制协议 / Triple HTTP/2 |
| 调用入口 | Feign 接口代理 | Dubbo 接口代理 |
| 服务端入口 | Controller | Service 实现类 |
| 连接方式 | HTTP 连接池、Keep-Alive | 通常是 Netty 长连接 |
| 调试方式 | curl / Postman 容易调试 | 需要 Dubbo 工具或 SDK |
| 适合场景 | 对外 API、跨语言、网关调用 | 内部服务、高并发 RPC、服务治理 |

---

## 二、Feign 的调用链路

典型组合：

```text
Spring Cloud OpenFeign
  + Nacos / Eureka / Consul
  + Spring Cloud LoadBalancer
  + OkHttp / Apache HttpClient / JDK HttpClient
  + Spring MVC
```

### 2.1 启动期链路

```text
应用启动
  -> 扫描 @EnableFeignClients
  -> 扫描 @FeignClient 标注的接口
  -> 解析接口上的 HTTP 注解
       @GetMapping
       @PostMapping
       @RequestParam
       @PathVariable
       @RequestBody
  -> 为接口生成动态代理对象
  -> 把代理对象注册到 Spring 容器
```

示例：

```java
@FeignClient(name = "user-service")
public interface UserClient {

    @GetMapping("/users/{id}")
    UserDTO getUser(@PathVariable("id") Long id);
}
```

业务代码里注入的是 `UserClient`，但真实对象不是接口实现类，而是 Feign 创建出来的代理对象。

### 2.2 请求调用链路

```text
业务代码调用 userClient.getUser(1)
  -> Feign 动态代理拦截方法调用
  -> 根据方法签名和注解构造 HTTP 请求
       GET /users/1
  -> Encoder 编码请求参数
  -> RequestInterceptor 添加 Header
       Token
       TraceId
       灰度标识
       租户标识
  -> 根据服务名 user-service 走服务发现
  -> 从注册中心拿到实例列表
  -> LoadBalancer 选择一个实例
  -> 底层 HTTP Client 发送请求
       OkHttp
       Apache HttpClient
       JDK HttpClient
  -> 网络传输 HTTP + JSON
  -> 服务提供方 HTTP Server 接收请求
       Tomcat
       Jetty
       Netty
  -> Spring MVC DispatcherServlet
  -> Controller 方法
  -> Service / DAO / DB
  -> 返回 JSON 响应
  -> Feign Decoder 反序列化响应
  -> 返回 Java 对象给业务代码
```

简化成：

```text
Feign 接口代理
  -> 拼 HTTP 请求
  -> 服务发现
  -> 负载均衡
  -> HTTP Client
  -> HTTP Server
  -> Controller
  -> JSON 响应
  -> Feign 解码
```

### 2.3 Feign 的链路特点

Feign 的优势：

- 基于 HTTP，通用性强。
- 对网关、浏览器、第三方系统友好。
- 跨语言成本低。
- curl、Postman 可以直接调试。
- 和 Spring MVC 注解体系结合自然。

Feign 的不足：

- 常见的 HTTP + JSON 调用链路报文更大。
- JSON 序列化、反序列化开销相对更高。
- 调用链路要经过 Controller 层，HTTP 语义更重。
- 服务治理能力依赖 Spring Cloud 生态组件组合。
- 高并发内部调用下，通常不如专门的 RPC 框架轻。

---

## 三、Dubbo 的调用链路

典型组合：

```text
Dubbo Consumer
  + Registry
  + Dubbo Proxy
  + Cluster / Router / LoadBalance / Filter
  + Netty Client
  + Dubbo Provider
```

### 3.1 Provider 启动期链路

```text
Provider 应用启动
  -> 扫描 Dubbo 服务
       @DubboService
       XML service 配置
  -> 创建 ServiceBean
  -> 暴露服务接口
  -> 启动协议服务
       dubbo://
       tri://
  -> 启动 Netty Server
  -> 向注册中心注册服务地址
       interface
       version
       group
       protocol
       host
       port
       methods
```

示例：

```java
@DubboService
public class UserServiceImpl implements UserService {

    @Override
    public UserDTO getUser(Long id) {
        return queryUser(id);
    }
}
```

### 3.2 Consumer 启动期链路

```text
Consumer 应用启动
  -> 扫描 @DubboReference
  -> 从注册中心订阅 Provider 地址
  -> 本地缓存 Provider 地址列表
  -> 为接口生成代理对象
  -> 和 Provider 建立连接
       通常是 Netty 长连接
```

示例：

```java
@DubboReference
private UserService userService;
```

业务代码里看起来是普通 Java 接口调用，实际会被 Dubbo 代理对象拦截。

### 3.3 请求调用链路

```text
业务代码调用 userService.getUser(1)
  -> Dubbo 代理对象拦截方法调用
  -> 构造 RPC 请求
       接口名
       方法名
       参数类型
       参数值
       version
       group
       attachment
  -> Cluster 层处理容错
       Failover
       Failfast
       Failsafe
       Forking
       Broadcast
  -> Router 路由筛选 Provider
       标签路由
       条件路由
       灰度路由
  -> LoadBalance 选择一个 Provider
       Random
       RoundRobin
       LeastActive
       ConsistentHash
  -> Consumer Filter 链处理
       trace
       monitor
       timeout
       auth
  -> 序列化请求
       Hessian2
       Fastjson2
       Protobuf
  -> Netty Client 通过长连接发送请求
  -> Provider Netty Server 接收请求
  -> 解码和反序列化
  -> 分发到 Dubbo 业务线程池
  -> Provider Filter 链处理
  -> 调用真正的 Service 实现类
  -> 序列化响应结果
  -> Netty 写回 Consumer
  -> Consumer 反序列化响应
  -> 返回 Java 对象给业务代码
```

简化成：

```text
Dubbo 接口代理
  -> 构造 RPC 请求
  -> 容错
  -> 路由
  -> 负载均衡
  -> Filter 链
  -> 序列化
  -> Netty 长连接
  -> Provider 业务线程池
  -> Service 实现类
  -> 响应返回
```

### 3.4 Dubbo 的链路特点

Dubbo 的优势：

- 面向内部服务调用，RPC 语义更直接。
- 常见 Dubbo2 协议是二进制协议，报文更紧凑。
- 基于 Netty NIO，连接复用能力强。
- Consumer 和 Provider 通常维护长连接。
- 原生支持注册发现、负载均衡、路由、容错、Filter、泛化调用等服务治理能力。
- 高并发、低延迟的内部 Java 服务调用场景下通常更有优势。

Dubbo 的不足：

- Dubbo2 私有协议对外部系统不如 HTTP 友好。
- 调试成本比 HTTP 高。
- 接口耦合更强，接口包、版本、兼容性要管理好。
- 运维和治理体系比普通 HTTP 调用更复杂。
- 如果业务线程池、数据库、缓存成为瓶颈，Netty 本身并不能解决整体吞吐问题。

---

## 四、并发量和性能怎么理解

可以这样理解：

> Dubbo 通常基于 Netty + 长连接 + 二进制序列化，网络 IO 和序列化开销较小，所以在 Java 内部服务间高并发 RPC 调用场景下，通常比 Feign + HTTP + JSON 更有性能优势。

但要注意三个点。

### 4.1 Feign 不是服务端

严格说，Feign 是客户端，不负责“接受请求”。

所以不要说：

```text
Feign 接受并发量比较少。
```

更准确的说法是：

```text
基于 Feign 的 HTTP 调用链路，在同等配置下，网络协议和序列化开销通常比 Dubbo RPC 更大。
```

真正接受请求的是服务端 HTTP 容器，例如：

```text
Tomcat
Jetty
Undertow
Netty
```

### 4.2 HTTP 不等于低并发

HTTP 也可以做高并发，例如：

```text
HTTP Keep-Alive
HTTP 连接池
HTTP/2 多路复用
OkHttp
Apache HttpClient
WebFlux + Netty
Nginx / Gateway
```

所以不能简单说 HTTP 一定并发低，只能说在常见的 `Feign + HTTP/JSON + Spring MVC` 链路下，整体开销通常比 `Dubbo + 长连接 + 二进制 RPC` 更大。

### 4.3 Dubbo 的瓶颈不只在 IO

Dubbo 使用 Netty，只说明网络层比较高效。真实并发能力还取决于：

```text
Provider 业务线程池大小
业务方法耗时
数据库连接池
缓存性能
序列化方式
请求报文大小
超时配置
重试配置
机器 CPU
机器内存
网络带宽
GC 情况
```

如果业务方法很慢，或者数据库已经打满，Dubbo 的 Netty 再高效也不能让整体系统无限抗并发。

---

## 五、面试表达

可以这样回答：

```text
Feign 和 Dubbo 都可以让远程调用看起来像本地方法调用，但它们的定位不一样。

Feign 是声明式 HTTP 客户端，底层通常通过 HTTP Client 发 HTTP 请求，
常见数据格式是 JSON，请求最终会进入对方服务的 Controller。
它的优点是简单、通用、跨语言友好、方便接入网关和第三方系统，
缺点是 HTTP/JSON 链路相对重一些，高并发内部调用下性能通常不如 RPC。

Dubbo 是 RPC 框架，Consumer 端也是通过代理对象拦截接口方法调用，
但它会把方法名、参数、接口信息封装成 RPC 请求，通过 Netty 长连接发送到 Provider，
Provider 反序列化后直接调用 Service 实现类。
Dubbo 原生支持注册发现、负载均衡、路由、容错、Filter 等服务治理能力，
并且常见 Dubbo2 协议是二进制协议，所以在内部服务高并发、低延迟调用场景下通常更有优势。

不过不能简单说 Dubbo 一定比 Feign 快，最终还要看业务耗时、线程池、数据库、缓存、序列化方式和网络环境。
如果是对外 API、跨语言、网关调用，我会优先考虑 Feign/HTTP；
如果是 Java 内部服务之间的大量 RPC 调用，我会优先考虑 Dubbo。
```

---

## 六、快速记忆

```text
Feign:
  Java 接口
    -> 动态代理
    -> HTTP 请求
    -> 服务发现 + 负载均衡
    -> Controller
    -> JSON 响应

Dubbo:
  Java 接口
    -> 动态代理
    -> RPC 请求
    -> 路由 + 负载均衡 + 容错
    -> Netty 长连接
    -> Service 实现类
    -> 二进制响应
```

一句话记忆：

```text
Feign 是 Java 方法调用 HTTP 接口。
Dubbo 是 Java 方法调用远程 Service。
```
