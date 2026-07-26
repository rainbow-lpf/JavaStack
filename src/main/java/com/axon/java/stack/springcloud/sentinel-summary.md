# 熔断降级（Sentinel）

> 目录：`com.axon.java.stack.springcloud`

---

## 一、一句话说清楚

> 下游服务挂了/慢了，上游不能跟着死等，要有"熔断"快速失败和"降级"兜底方案，防止故障雪崩扩散。

---

## 二、雪崩效应

```
正常时：

  Order-Service → User-Service → 正常返回（20ms）

User-Service 挂掉后：

  Order-Service → User-Service → 超时等待 10s
                                         │
  100 个并发请求都在等 User-Service ──────┘
  线程池被占满 → Order-Service 也不可用
  → 上游调用 Order 也一起挂 → 一层一层往外扩 → 全站瘫痪
```

---

## 三、Sentinel 三大核心能力

| 能力 | 说明 | 类比 |
|------|------|------|
| **流控（限流）** | QPS / 线程数超过阈值就挡掉 | 景区限流，每天最多入园 5 万人 |
| **熔断（断路）** | 下游错误率过高 → 直接断掉，过一段试一次 | 保险丝熔断 |
| **降级（fallback）** | 下游不可用时返回兜底数据 | 拿不到详情 → 返回缓存里的简要信息 |

---

## 四、流控规则（限流）

### 4.1 三种流控模式

| 模式 | 说明 | 示例 |
|------|------|------|
| **直接** | 对这个资源做限流 | `/user/get` 超过 10 QPS 直接拒绝 |
| **关联** | 关联资源达到阈值，限流当前资源 | `/user/get` 超过 50 QPS → 限 `/user/list` |
| **链路** | 只限制某个入口调这个资源 | 入口 A 调 `/common` 限流，入口 B 不 |

### 4.2 两种流控效果

| 效果 | 行为 | 实现 |
|------|------|------|
| **快速失败** | 超出直接抛异常 `BlockException` | 默认，保护最直接 |
| **Warm Up** | 阈值从低到高慢慢放大（预热期） | 防止冷启动时一波流量打垮链路 |
| **排队等待** | 线程排队，匀速通过 | 匀速排队模式，漏桶 |

### 4.3 限流算法

```
1. 计数器（固定窗口）
   第 0~1s → 最多 10 个请求
   问题：0.99s 来 10 个 + 1.01s 来 10 个 → 实际上 20ms 内进来 20 个 → 打垮

2. 滑动窗口（Sentinel 默认）
   窗口分成多个小格子，随时间滑动，统计更平滑
   解决了固定窗口边界的突发问题

3. 令牌桶
   以固定速率往桶里放令牌，请求先拿令牌才放行
   适用于：允许突发（桶容量够大就能兜住一波峰值）

4. 漏桶
   固定速率流出，进来的快但出去的慢 → 排队
   适用于：需要均匀限速（不允许突发）
```

---

## 五、熔断规则

### 5.1 三种熔断策略

| 策略 | 触发条件 | 适用 |
|------|---------|------|
| **慢调用比例** | 慢调用超阈值（比如响应 > 200ms 的比例 > 50%） | 下游变调只会更慢 |
| **异常比例** | 异常率超阈值（如 > 50%） | 下游疯狂抛异常 |
| **异常数** | 一分钟内异常数超阈值 | 下游挂了，异常激增 |

### 5.2 熔断状态机

```
     CLOSED（正常通过）
         │
         │ 慢调用比例/异常比例超过阈值
         ▼
      OPEN（直接拒绝，快速失败）
         │
         │ 经过时间窗口（如 10s）
         ▼
   HALF-OPEN（放行一个请求试探）
      /        \
    成功         失败
     │             │
     ▼             ▼
  CLOSED       OPEN（继续熔断）
```

---

## 六、降级（Fallback）

```java
@RestController
public class UserController {

    @GetMapping("/user/{id}")
    @SentinelResource(
        value = "getUser",
        blockHandler = "getUserBlockHandler",      // 限流/熔断触发
        fallback = "getUserFallback"                // 业务异常触发
    )
    public User getUser(@PathVariable Long id) {
        return userService.getById(id);  // 可能挂或超时
    }

    // 限流/熔断 → 走这个
    public User getUserBlockHandler(Long id, BlockException e) {
        return User.fallback();  // 返回兜底对象
    }

    // 业务异常 → 走这个
    public User getUserFallback(Long id, Throwable t) {
        return User.fallback();
    }
}
```

---

## 七、整合 OpenFeign

```java
// 1. Feign 接口
@FeignClient(
    name = "user-service",
    fallbackFactory = UserServiceFallbackFactory.class  // 降级工厂
)
public interface UserServiceClient {
    @GetMapping("/user/{id}")
    User getById(@PathVariable("id") Long id);
}

// 2. 降级实现
@Component
public class UserServiceFallbackFactory implements FallbackFactory<UserServiceClient> {
    @Override
    public UserServiceClient create(Throwable cause) {
        return id -> {
            log.error("调用 user-service 失败, id={}, cause={}", id, cause.getMessage());
            return User.fallback();
        };
    }
}

// 3. 配置 Sentinel 支持 Feign
feign:
  sentinel:
    enabled: true
```

---

## 八、持久化配置（Nacos 数据源）

Sentinel 规则默认存在内存中，重启就丢。接 Nacos 持久化：

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        flow:
          nacos:
            server-addr: 127.0.0.1:8848
            dataId: user-service-flow-rules
            groupId: DEFAULT_GROUP
            rule-type: flow                # flow/degrade/authority
```

---

## 九、Sentinel vs Hystrix

| 维度 | Sentinel | Hystrix |
|------|----------|---------|
| **状态** | 持续维护 | 已停更 |
| **限流算法** | 滑动窗口 + 漏桶 | 信号量 + 线程池 |
| **规则动态配置** | ✅ 秒级生效 | ❌ 需代码中配 |
| **控制台** | ✅ 强，实时监控 | ✅ dashboard |
| **资源定义** | 任意方法 | 线程池或信号量 |
| **整合生态** | Spring Cloud / Dubbo / gRPC | Spring Cloud |

---

## 十、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| blockHandler 没生效 | 方法签名不对（参数 + BlockException） | 签名必须和原方法一致，额外加 BlockException |
| fallback 和 blockHandler 都写了，走哪个 | blockHandler 管限流/熔断，fallback 管业务异常 | 两个同时配 → 限流优先走 blockHandler |
| 控制台看不到规则 | Sentinel 同步到 Nacos 没配 / 没开 | 配置 datasource 持久化 |
| 限流规则重启丢失 | 存内存 | 接入 Nacos 持久化 |

---

## 十一、面试话术（30 秒版）

> Sentinel 提供流量控制、熔断降级、系统负载保护三大能力。默认用滑动窗口算法做限流，比固定窗口更平滑。熔断有三种策略——慢调用比例、异常比例、异常数，状态机是 CLOSED → OPEN → HALF-OPEN → CLOSED。结合 OpenFeign 加 fallbackFactory 实现降级兜底，规则持久化接入 Nacos 防止重启丢失。
