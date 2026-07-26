# 面试高频题 — 综合总结

> 目录：`com.axon.java.stack.mianshiti`

---

## 一、Spring Cloud vs Dubbo

### 1.1 一句话分清

> **Spring Cloud = 微服务全家桶。Dubbo = 高性能 RPC 框架。**

| 维度 | Spring Cloud | Dubbo |
|------|------|------|
| **定位** | 微服务全栈解决方案 | RPC 远程调用框架 |
| **通信协议** | HTTP/REST（默认） | Dubbo 协议（自定义二进制，高性能） |
| **服务注册与发现** | Eureka / Consul / Nacos | Zookeeper / Nacos |
| **负载均衡** | Ribbon / LoadBalancer | 内置 |
| **熔断降级** | Hystrix / Sentinel | Sentinel（需集成） |
| **API 网关** | Zuul / Gateway | ❌ 无 |
| **配置中心** | Config / Nacos | ❌ 无（需外挂） |
| **分布式跟踪** | Sleuth + Zipkin | ❌ 无（需外挂） |
| **学习成本** | 组件多，学习曲线陡 | 专注 RPC，上手快 |
| **生态** | Spring 全家桶，无缝集成 | 独立框架，更好集成 Spring Boot |

### 1.2 选型建议

| 场景 | 推荐 |
|------|------|
| 需要全套微服务治理（网关、配置、熔断、追踪） | Spring Cloud |
| 纯服务间高性能调用，对延迟敏感 | Dubbo |
| 技术栈以 Spring 为核心 | Spring Cloud |
| 超高吞吐量 RPC 调用（电商、支付） | Dubbo |

---

## 二、HTTP vs HTTPS

### 2.1 一句话说清楚

> **HTTPS = HTTP + SSL/TLS 加密层。明文 vs 加密的区别。**

| 维度 | HTTP | HTTPS |
|------|------|------|
| **安全性** | ❌ 明文传输 | ✅ SSL/TLS 加密 |
| **端口** | 80 | 443 |
| **证书** | ❌ 无 | ✅ 需要 CA 颁发的 SSL 证书 |
| **速度** | 快（无加密开销） | 稍慢（多加解密），现代优化已缩小差距 |
| **浏览器显示** | "不安全"警告 | 🔒 锁形图标 |
| **SEO** | 排名低 | 排名更高 |
| **中间人攻击** | ❌ 可被窃听、篡改 | ✅ 加密防篡改 |

### 2.2 HTTPS 握手流程（面试版）

```
① 客户端 → 服务器：你好，我支持的加密算法有 xxx
② 服务器 → 客户端：这是我的 SSL 证书 + 公钥
③ 客户端：校验证书合法性（CA 签名）
④ 客户端：生成一个随机密钥 → 用服务器公钥加密 → 发给服务器
⑤ 服务器：用自己私钥解密 → 拿到随机密钥
⑥ 双方：用这个随机密钥对称加密通信（AES 等）
```



### 2.3 什么场景必须用 HTTPS？

```
登录页面、支付页面、用户个人信息、API 接口
→ 涉及密码、手机号、身份证、银行卡号等敏感数据
```

---

## 三、RabbitMQ vs RocketMQ

| 维度 | RabbitMQ | RocketMQ |
|------|------|------|
| **开发语言** | Erlang | Java |
| **协议** | AMQP / MQTT / STOMP | 自定义 TCP 协议 | 万级 | 十万级 |
| **延迟** | 微秒级 | 毫秒级 |
| **顺序消息** | ❌ 不原生支持 | ✅ 原生支持 |
| **事务消息** | 支持（性能差） | ✅ 两阶段提交，高性能 |
| **消息回溯** | ❌ | ✅ 按时间/偏移量回溯 |
| **消息过滤** | 按 RoutingKey | ✅ Broker 端 Tag / SQL 过滤 |
| **定时/延迟消息** | 死信队列 + TTL 模拟 | ✅ 18 个延迟级别 |
| **高可用** | 镜像队列 | 主从 + 自动切换 |
| **社区** | 全球范围广，Spring 亲儿子 | 国内阿里生态，中文文档友好 |

---

## 四、面试话术

### Spring Cloud vs Dubbo（20 秒版）

> Spring Cloud 微服务全家桶，HTTP/REST 通信，自带网关、配置中心、熔断追踪。Dubbo 高性能 RPC 框架，二进制协议，通信效率高，但需要外挂网关和配置。想要全套用 Spring Cloud，想要高性能调用用 Dubbo。

### HTTP vs HTTPS（15 秒版）

> HTTPS = HTTP + SSL/TLS。HTTP 明文传输，80 端口，可被窃听篡改。HTTPS 加密传输，443 端口，需要 CA 证书，防中间人攻击。现在所有涉及敏感数据的网站都强制 HTTPS。

### RabbitMQ vs RocketMQ（20 秒版）

> RabbitMQ 是 Erlang 写的，AMQP 协议，微秒延迟，复杂路由强，万级吞吐。RocketMQ 是 Java 写的，阿里出品，原生支持顺序消息和事务消息，十万级吞吐，消息可回溯。电商金融高并发用 RocketMQ，复杂路由中小流量用 RabbitMQ。
