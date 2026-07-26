# Gateway 网关详解

> 目录：`com.axon.java.stack.springcloud`

---

## 一、为什么需要网关

在微服务架构中，Gateway 就像**整个系统的"大门"**——所有外部请求不打散到各个微服务，而是统一从 Gateway 进来。

### 1.1 没有网关的架构

```
客户端 → 用户服务 (192.168.1.10:8081)
客户端 → 订单服务 (192.168.1.11:8082)
客户端 → 商品服务 (192.168.1.12:8083)
客户端 → 支付服务 (192.168.1.13:8084)
```

**问题：**
- 前端需要知道所有微服务地址
- 每个服务自己实现鉴权、限流、日志
- 前后端接口粒度不匹配（前端要一个页面数据，后端给 5 个接口）
- 跨域问题每个服务都要配

### 1.2 有了网关的架构

```
客户端 → Gateway (api.example.com:80)
              ├→ 用户服务
              ├→ 订单服务
              ├→ 商品服务
              └→ 支付服务
```

---

## 二、网关核心作用

| 作用 | 说明 | 类比 |
|------|------|------|
| **统一入口** | 前端只需知道一个 Gateway 地址 | 小区门禁 |
| **路由转发** | `/order/**` → 订单服务，`/user/**` → 用户服务 | 快递分拣 |
| **鉴权认证** | 登录校验、Token 验证在网关层一次搞定 | 门卫查证件 |
| **限流熔断** | 令牌桶/漏桶流控，防流量洪峰打垮下游 | 景区限流 |
| **日志监控** | 统一记录请求链路、响应时间 | 监控摄像头 |
| **跨域处理** | CORS 配置集中管理，不用每个服务配 | — |
| **协议转换** | 对外 HTTP/REST → 对内 Dubbo/gRPC | 翻译官 |
| **负载均衡** | 将流量均匀分发到多实例 | 分流交警 |

---

## 三、请求执行流程

```
请求到达 Gateway
     │
     ├→ 1. 路由断言（Predicate）
     │      匹配 URL、Header、参数等，决定路由到哪个服务
     │
     ├→ 2. 过滤器链（Filter Chain）
     │      ├→ 前置过滤器：鉴权、限流、日志、参数校验
     │      ├→ 转发请求到下游服务
     │      └→ 后置过滤器：响应加工、添加响应头
     │
     └→ 3. 返回给客户端
```

---

## 四、主流网关框架对比

| 维度 | Spring Cloud Gateway | Zuul 1.x | Zuul 2.x | Nginx + Lua | Kong |
|------|------|------|------|------|------|
| **实现语言** | Java | Java | Java | C + Lua | OpenResty |
| **通信模型** | Netty（异步非阻塞） | Servlet 2.5（同步阻塞） | Netty（异步非阻塞） | 异步非阻塞 | 异步非阻塞 |
| **性能** | 高（接近 Netty） | 低（Tomcat 线程池） | 高 | 极高 | 高 |
| **Spring 集成** | ✅ 原生无缝 | ✅ 原生 | ❌ | ❌ | ❌ |
| **动态路由** | ✅ | ❌ | ✅ | ❌（需 Lua 脚本） | ✅ |
| **扩展性** | ✅ Filter + Predicate | ✅ Filter | ✅ Filter | ❌ 脚本维护难 | ✅ 插件市场 |
| **适用场景** | Spring 全栈项目 | 旧项目（已弃用） | Netflix 体系 | 高性能静态网关 | 独立网关层 |

---

## 五、Spring Cloud Gateway 核心三要素

### 5.1 Route（路由）

路由是网关的基本构建块，包含：
- **ID**：唯一标识
- **目标 URI**：转发地址
- **Predicates**：匹配规则
- **Filters**：过滤处理器

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service              # 负载均衡到 User 服务
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=1                    # 去掉 /api
```

### 5.2 Predicate（断言）

类似 Java8 的 `Predicate`，匹配请求条件：

| Predicate | 说明 | 示例 |
|-----------|------|------|
| `Path` | 按路径匹配 | `Path=/api/user/**` |
| `Header` | 按请求头匹配 | `Header=X-Token, \d+` |
| `Method` | 按请求方法 | `Method=GET,POST` |
| `Query` | 按查询参数 | `Query=name, zhangsan` |
| `Cookie` | 按 Cookie | `Cookie=sessionId, .+` |
| `Host` | 按域名 | `Host=**.user.com` |
| `Before/After/Between` | 按时间 | `After=2024-01-01T00:00:00+08:00` |
| `RemoteAddr` | 按 IP | `RemoteAddr=192.168.1.0/24` |
| `Weight` | 按权重（灰度） | `Weight=group1, 80` |

### 5.3 Filter（过滤器）

#### 内置 Filter

| Filter | 说明 |
|--------|------|
| `StripPrefix` | 去掉路径前缀 `/api/user/hello` → `/hello` |
| `PrefixPath` | 添加路径前缀 |
| `AddRequestHeader` | 添加请求头 |
| `AddRequestParameter` | 添加请求参数 |
| `AddResponseHeader` | 添加响应头 |
| `RewritePath` | 路径重写 |
| `Retry` | 请求重试 |
| `CircuitBreaker` | 熔断（集成 Resilience4j） |
| `RequestRateLimiter` | 限流（集成 Redis 令牌桶） |

#### 自定义 Filter（GlobalFilter）

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 校验 Token
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 2. 放行
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100;  // 越小越优先
    }
}
```

---

## 六、限流方案

### 令牌桶算法（RequestRateLimiter + Redis）

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10    # 每秒补充令牌数
                redis-rate-limiter.burstCapacity: 20    # 令牌桶最大容量
                key-resolver: "#{@ipKeyResolver}"       # 限流维度
```

---

## 七、跨域配置

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowedMethods: "*"
            allowedHeaders: "*"
            allowCredentials: true
            maxAge: 3600
```

---

## 八、高可用架构

```
              Nginx (VIP)         ← 主备高可用
              /       \
    Gateway-1         Gateway-2   ← 多实例 + 注册中心感知
      (lb://)          (lb://)
         |                |
    ┌────┴────┐    ┌────┴────┐
    User   Order   User   Order
```

---

## 九、Nginx 能替代 Gateway 吗

Nginx 本身就能做反向代理，很多小项目直接用 Nginx 就够。

### 9.1 Nginx vs Gateway 关键区别

| 维度 | Nginx | Spring Cloud Gateway |
|------|-------|---------------------|
| **定位** | 通用反向代理 / Web 服务器 | 微服务 API 网关 |
| **路由方式** | 静态配置 `nginx.conf`，改完要 reload | 动态路由，服务上下线自动感知（注册中心） |
| **鉴权** | Lua 脚本 / `auth_request` 模块 | 过滤器链，写 Java 就行 |
| **限流** | `limit_req` / `limit_conn` | 令牌桶 + Redis，可精细化控制 |
| **熔断** | ❌ 不支持 | ✅ 集成 Resilience4j / Sentinel |
| **性能** | 极高（C 语言，10w+ QPS） | 高（Netty，万级 QPS） |
| **动态感知** | ❌ 不知道服务上下线 | ✅ 接注册中心，自动感知实例变化 |
| **灰度发布** | 自己写 Lua | Predicate + Weight 轻松实现 |
| **配置中心** | ❌ 无 | ✅ 集成 Nacos / Apollo 动态刷新路由 |

### 9.2 什么时候用 Nginx 直连就够了

```
满足以下条件 → Nginx 足够，不用上 Gateway：
  ✅ 服务数量少（3~5 个）
  ✅ 服务地址固定，不频繁变动
  ✅ 不需要动态路由和自动服务发现
  ✅ 鉴权逻辑简单（静态 Token 校验）
  ✅ 不需要熔断降级
  ✅ 追求极致性能（10w+ QPS）
```

### 9.3 Nginx 反向代理示例

```nginx
upstream user-service {
    server 192.168.1.10:8081;
    server 192.168.1.11:8081;
}

upstream order-service {
    server 192.168.1.10:8082;
    server 192.168.1.11:8082;
}

server {
    listen 80;
    server_name api.example.com;

    # 限流：每秒 10 个请求
    limit_req_zone $binary_remote_addr zone=mylimit:10m rate=10r/s;

    location /api/user/ {
        limit_req zone=mylimit burst=20;
        proxy_pass http://user-service/;
    }

    location /api/order/ {
        proxy_pass http://order-service/;
    }
}
```

### 9.4 什么时候必须上 Gateway

```
以下场景 Nginx 扛不住，必须上 Gateway：
  ❌ 服务实例动态扩缩容，IP 频繁变动
  ❌ 需要根据注册中心自动感知服务上下线（lb://）
  ❌ 鉴权逻辑复杂（查 Redis Token、白名单、角色权限）
  ❌ 需要熔断降级（下游挂了自动 fallback）
  ❌ 需要灰度发布（按权重、Header 分流到不同版本）
  ❌ 需要请求聚合（一个接口聚合多个微服务结果）
```

### 9.5 双层架构：Nginx + Gateway

```
客户端 → Nginx（SSL终结 + 静态资源 + 第一层限流）
              → Gateway（动态路由 + Token校验 + 熔断 + 灰度 + 日志）
                     → 微服务集群
```

### 9.6 云上标准三层架构：SLB → Nginx → Gateway

在云环境（阿里云/AWS），最标准的架构是三层：

```
                        公网
                         │
                    ┌─────▼─────┐
                    │    SLB     │  ← 四层/七层负载均衡 + 弹性公网 IP + 健康检查
                    │ (多可用区)  │     消除单点故障
                    └──┬───┬────┘
                       │   │
                 ┌─────▼┐ ┌▼─────┐
                 │Nginx-1│ │Nginx-2│  ← SSL 终结 + 静态资源 + 二级路由 + IP 黑名单
                 └──┬───┘ └──┬───┘
                    │        │
              ┌─────▼──┐ ┌──▼─────┐
              │Gateway-1│ │Gateway-2│  ← 动态路由 + Token 校验 + 熔断 + 灰度
              └──┬─────┘ └──┬─────┘
                 │           │
            ┌────┴───┐ ┌────┴───┐
            │ User   │ │ Order  │  ← 微服务集群
            │ Service│ │Service │
            └────────┘ └────────┘
```

| 层 | 工具 | 职责 | 为什么需要这一层 |
|----|------|------|-----------------|
| **负载均衡层** | SLB / ALB / CLB | 四层/七层分流、健康检查、多可用区 HA、弹性公网 IP | 消除公网入口单点故障，自动切换故障节点 |
| **反向代理层** | Nginx | SSL 卸载、静态资源、二级路由、IP 黑名单、第一层流控 | Gateway 不适合处理静态文件；证书集中管理；扛住大部分无效流量 |
| **网关层** | Gateway | 动态路由、Token 校验、熔断降级、灰度发布、日志链路 | 注册中心感知服务上下线；业务级鉴权限流；灰度分流 |
| **服务层** | 微服务 | 业务逻辑 | 专注业务，不关心安全/路由/限流 |

### 9.7 为什么说"SLB 扛连接"，Nginx 不行吗

Nginx 当然能扛连接，**区别不在"能不能"，而在"高可用"。**

```
方案 A — Nginx 单干：
  公网 IP → Nginx（一台机器）
               ├→ 微服务
  致命伤：Nginx 挂了 → 全站瘫痪，这就是单点故障（SPOF）

方案 B — SLB + Nginx：
  公网弹性 IP → SLB（云服务，多可用区，自动故障切换）
                    ├→ Nginx-1（活着）
                    └→ Nginx-2（活着）
  SLB 本身是云服务，不会挂，背后 Nginx 挂一台也能自动踢掉
```

**Nginx 自己做高可用也行，就是折腾：**

```
keepalived 方案（自己搞）:

         VIP (虚拟 IP，漂来漂去)
           ├→ Nginx-1 (Master，VIP 挂这)
           └→ Nginx-2 (Backup，Master 挂了 VIP 飘过来)

痛点：
  - keepalived 配置复杂，脑裂风险
  - 需要额外服务器资源
  - 升级、打补丁、宕机恢复全自己管
  - 云上不如直接买个 SLB 省心
```

**SLB vs Nginx 自建高可用：**

| 维度 | SLB | Nginx + keepalived |
|------|-----|-------------------|
| **实现方式** | 云服务，买即用 | 自己搭 keepalived + VIP |
| **单点故障** | ❌ 无（跨可用区） | 理论无，实际有脑裂风险 |
| **弹性公网 IP** | ✅ 绑定即用，IP 永不变 | ❌ 服务器 IP 固定，迁移就变 |
| **运维成本** | 零，云厂商保障 99.99% | 自己管，出问题自己扛 |
| **四层转发** | ✅ 原生 TCP/UDP | 需 stream 模块（一般只用七层） |
| **健康检查** | 内置，自动摘除故障节点 | 靠 keepalived 脚本 |

> SLB 本质上就是云厂商帮你管好了 keepalived 方案，免运维。Nginx 能扛连接，但 Nginx 本身是单点——SLB 解决的是"入口第一跳就不能挂"的问题。

### 9.8 为什么 SLB 不能直接接 Gateway

```
问题 1: Gateway 没有公网防护
  → SLB 提供 DDoS 基础防护、流量清洗，Gateway 是应用层没有这些

问题 2: 静态资源处理
  → Gateway 处理静态文件浪费 CPU，Nginx 专门干这个，效率高 10 倍

问题 3: SSL 证书集中管理
  → 证书配在 Nginx 层一处，Gateway 解 HTTP 明文，性能更好

问题 4: 架构分层原则
  → SLB 扛连接 → Nginx 扛路由 → Gateway 扛业务 → 逐层卸载，各不越界
```

### 9.9 架构演进路线

```
阶段一（小项目）:
  客户端 → 微服务
  问题：无统一入口，无鉴权，无限流

阶段二（加 Nginx）:
  客户端 → Nginx → 微服务
  解决：统一入口 +静态 + 基础限流

阶段三（加 Gateway）:
  客户端 → Nginx → Gateway → 微服务
  解决：动态路由 + 鉴权 + 熔断 + 灰度

阶段四（云化）:
  公网 → SLB → Nginx → Gateway → 微服务
  解决：高可用 + 弹性 IP + 跨可用区容灾

阶段五（终极形态）:
  公网 → CDN/WAF → SLB → Nginx → Gateway → 微服务
  解决：全站加速 + Web 应用防火墙 + 全链路防护

关于 CDN，详见下方 9.10 节。
```

### 9.10 CDN 是什么，在架构中起什么作用

CDN（Content Delivery Network，内容分发网络），核心就一句话：**把数据提前搬到离用户最近的机房，用户就近访问，不用绕远路。**

**两个核心价值：**

**一、加速静态资源**

```
没有 CDN：
  北京用户 → 访问图片 → 服务器在上海 → 跨 1000+ 公里 → 延迟 50ms+

有 CDN：
  北京用户 → 访问图片 → 北京 CDN 节点（命中缓存） → 延迟 5ms
  上海用户 → 访问图片 → 上海 CDN 节点（命中缓存） → 延迟 5ms
  深圳用户 → 访问图片 → 深圳 CDN 节点（命中缓存） → 延迟 5ms
```

静态资源（JS / CSS / 图片 / 视频 / 字体）缓存在全国数百个 CDN 边缘节点上，用户就近拉取，95%+ 请求不回流到源站。

**二、隐藏源站 + 抗 DDoS**

```
用户 → CDN → 缓存未命中 → 回源 SLB → Nginx → Gateway → 微服务

CDN 挡在最前面：
  - 源站 IP 完全不暴露给公网
  - DDoS 攻击流量被 CDN 各地节点分散消化（一个节点扛不住，全部节点一起扛）
  - CDN 自带流量清洗能力，到源站的只是正常请求
  - 源站带宽成本大幅降低（回源率通常 < 5%）
```

**CDN 能缓存什么：**

| 类型 | 内容 | 缓存时长 | 回源频率 |
|------|------|---------|---------|
| 静态资源 | JS / CSS / 图片 / 字体 / 视频 | 天~周级 | 极低（<1%） |
| 半静态 | HTML 页面首页 | 分钟级 | 低 |
| 动态 API | `/api/*` 接口数据 | 不缓存 | 每次回源 |

**CDN 回源时序（缓存未命中时）：**

```
用户 → 北京 CDN 节点
          │ 缓存未命中
          ▼
        回源 SLB → Nginx → Gateway → 微服务
          │
          ▼
        拿到数据 → 缓存到北京 CDN 节点 → 返回给用户
        下次同一请求 → 直接命中缓存，不再回源
```

> CDN = 全国/全球分布的缓存代理网络。把数据搬到离用户最近的地方，加速访问 + 隐藏源站 + 抗 DDoS + 省带宽。静态资源命中率 95% 以上，是终极形态的第一道关卡。

---

## 十、SLB 底层原理

### 10.1 高可用 IP：为什么 SLB 的 IP 不会挂

云厂商底层用 **ECMP（等价多路径路由）+ BGP 协议**，同一个公网 IP 在多个机房同时宣告。

```
你的域名 → DNS 解析到 SLB 公网 IP（比如 1.2.3.4）
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
    可用区 A           可用区 B          可用区 C
   [SLB 实例]        [SLB 实例]       [SLB 实例]
   (1.2.3.4)        (1.2.3.4)       (1.2.3.4)
       │                 │                │
    Nginx-1          Nginx-2         Nginx-3

关键：同一个 IP 1.2.3.4 在三个可用区同时存在
      BGP 协议自动把流量引到最近的活着的节点
      可用区 A 挂了 → 路由自动收敛到 B 和 C，上层无感知
```

**vs keepalived 本质区别：**

| | keepalived | SLB |
|--|-----------|-----|
| IP 漂移方式 | VIP 在主备之间飘（有切换耗时） | IP 多机房同时宣告，无切换 |
| 切换耗时 | 几秒（ARP 广播） | 毫秒级（BGP 收敛） |
| 跨机房 | ❌ 同网段才能飘 | ✅ 跨可用区原生支持 |

### 10.2 四层 SLB（L4）：TCP/UDP 层转发

不解析 HTTP 内容，只看 IP + 端口，性能极高。

**三种工作模式：**

```
1. DR 模式（直接路由，最高性能）
   客户端 → SLB（把 MAC 地址改成后端） → 后端 → 直接回客户端
   特点：请求进、响应绕，SLB 不管回包，吞吐量最高

2. NAT 模式（网络地址转换，默认）
   客户端 → SLB（改目标 IP） → 后端 → SLB（改源 IP） → 客户端
   特点：进出都过 SLB，SLB 带宽会成为瓶颈

3. FULLNAT 模式（双向地址转换 —— 阿里云 LVS 采用）
   客户端 → SLB（源 IP + 目标 IP 全改） → 后端 → SLB（全改回来） → 客户端
   特点：后端看不到客户端真实 IP（需要通过 X-Forwarded-For 获取）
        好处：后端和 SLB 不必在同一子网，跨网段部署灵活
```

**四层 vs 七层：**

| | 四层 SLB (L4) | 七层 SLB (L7) |
|--|-------------|-------------|
| 解析层 | TCP/UDP（IP + 端口） | HTTP/HTTPS（URL + Header + Cookie） |
| 转发依据 | IP + 端口 | URL / Header / Cookie / Host |
| 性能 | 极高（内核级转发，DPDK） | 高（需解析 HTTP） |
| 典型能力 | 轮询转发 | 按 URL 路由、会话保持、SSL 卸载 |

### 10.3 七层 SLB（L7）：HTTP/HTTPS 层转发

七层解析 HTTP 内容，能按 URL、Header、Cookie 做路由。

```
请求到达七层 SLB
   │
   ├→ SSL 卸载（TLS 握手，拿到明文 HTTP）
   ├→ 解析请求：GET /api/user  Host: api.example.com
   ├→ 按规则匹配：
   │     /api/user/* → user 后端组
   │     /api/order/* → order 后端组
   ├→ 选一个后端实例（轮询 / 最小连接 / IP 哈希）
   └→ 建立新连接 → 转发
```

### 10.4 负载均衡算法

| 算法 | 说明 | 适用场景 |
|------|------|---------|
| **轮询（RR）** | 挨个轮 | 后端配置一样 |
| **加权轮询（WRR）** | 按权重比例分发 | 后端配置不同（4核 vs 8核） |
| **最小连接数** | 发给当前连接数最少的 | 长连接场景（WebSocket） |
| **源 IP 哈希** | 同一客户端 IP 固定到同一个后端 | 需要会话保持 |
| **一致性哈希** | 后端增减时只影响极少量请求 | 缓存场景，减少缓存雪崩 |
| **最快响应** | 发给响应时间最短的 | 后端性能参差不齐 |

### 10.5 健康检查

```
SLB 不停向后端发健康检查探测：
  间隔 2s 一次，超时 3s，连续失败 3 次 → 踢掉该节点
  连续成功 3 次 → 重新加回

检查类型：
  - TCP 探测：端口通不通
  - HTTP GET：返回 2xx/3xx 就算健康
  - 自定义 URL：/health 返回业务状态（DB 连不上也可标记不健康）
```

### 10.6 完整请求链路

```
1. 客户端 DNS 解析 api.example.com → 拿到 SLB 公网 IP
2. TCP 三次握手 → SLB 接到连接
3. TLS 握手（证书挂在 SLB 上）→ SSL 卸载，拿到明文 HTTP
4. 解析 HTTP 请求 → 匹配路由规则 → 选出一个后端实例
5. SLB 建立新 TCP 连接 → 转发请求到后端
6. 后端处理 → 响应原路返回
7. SLB 开启连接复用（长连接池），减少 TCP 握手开销
```

> SLB 底层 = **BGP 高可用 IP 多机房同时宣告** + **DPDK 内核旁路高速转发** + **七层 HTTP 协议解析** + **健康检查自动摘除故障节点**。云厂商把这一整套封装成服务。

---

## 十一、面试话术（30 秒版）

> Gateway 是微服务统一入口，负责路由转发、鉴权限流、熔断灰度。核心三要素：Route、Predicate、Filter，底层 Netty 异步非阻塞。生产环境云上标准是三层架构：**SLB 做公网入口 + 高可用 → Nginx 做 SSL + 静态 + 限流 → Gateway 做动态路由 + 业务过滤**，逐层卸载，各司其职。
