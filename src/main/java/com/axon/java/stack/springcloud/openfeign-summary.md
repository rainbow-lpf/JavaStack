# 服务远程调用（OpenFeign + LoadBalancer）

> 目录：`com.axon.java.stack.springcloud`

---

## 一、一句话说清楚

> OpenFeign = 声明式 HTTP 客户端，写个 Interface 加上注解就能远程调用，底层用 JDK 动态代理自动拼 HTTP 请求。

---

## 二、核心概念

```
以前（RestTemplate 硬编码）:

  String url = "http://192.168.1.10:8081/user/get?id=" + id;
  User user = restTemplate.getForObject(url, User.class);

  ❌ 服务地址写死，实例挂了直接超时
  ❌ URL 拼手误，参数容易漏

现在（OpenFeign 声明式）:

  @FeignClient(name = "user-service", path = "/user")
  public interface UserServiceClient {
      @GetMapping("/get")
      User getById(@RequestParam("id") Long id);
  }

  ✅ 直接注入用，像调本地方法一样
  ✅ 结合注册中心，地址自动发现
  ✅ 集成 LoadBalancer，自动负载均衡
```

---

## 三、底层原理

### 3.1 JDK 动态代理

```
UserServiceClient client → 实际注入的是一个 Proxy 对象

调用 client.getById(1L):
  1. Proxy → FeignInvocationHandler.invoke()
  2. 取出方法上 @FeignClient + @RequestMapping 注解
  3. 拼出完整 URL：user-service/user/get?id=1
  4. 通过 LoadBalancer 选一个 user-service 实例
  5. 发起 HTTP 请求 → 解析返回结果
```

### 3.2 请求拦截器（RequestInterceptor）

```java
@Component
public class TokenInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        // 统一加 Token 头，所有 Feign 调用自动带
        template.header("Authorization", "Bearer xxx");
    }
}
```

---

## 四、负载均衡（Spring Cloud LoadBalancer）

Spring Cloud 已全面用 LoadBalancer 替代 Ribbon（后者进入维护模式）。

### 4.1 负载均衡流程

```
@FeignClient(name = "user-service")
   │
   ↓
OpenFeign → 拼好请求，但还不知道具体 IP
   │
   ↓
LoadBalancer → DiscoveryClient.getInstances("user-service") 拉出 3 个实例
   │
   ↓
IRule（负载规则）选一个：
  - RoundRobinRule（轮询，默认）
  - RandomRule（随机）
  - NacosWeightedRule（权重，配合 Nacos 实例权重）
   │
   ↓
拿到具体 http://192.168.1.11:8081/user/get?id=1 → 发起请求
```

### 4.2 几种负载策略

| 策略 | 说明 |
|------|------|
| 轮询（RoundRobin） | 挨个轮，默认 |
| 随机（Random） | 随机挑一个 |
| 权重（Weight） | 配置高的多分流量（Nacos 实例可设权重 0~1） |
| 最小连接数 | 发给当前负载最低的实例（需自定义） |
| Zone 优先 | 优先同机房实例，跨机房 fallback |

---

## 五、OpenFeign 基础配置

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:                 # 全局配置
            connect-timeout: 5000
            read-timeout: 10000
            logger-level: BASIC    # NONE/BASIC/HEADERS/FULL
          user-service:            # 按服务单独配置
            read-timeout: 3000
      compression:
        request:
          enabled: true
          min-request-size: 2048   # 超 2KB 才压缩
```

### FeignClient 常用注解

```java
@FeignClient(
    name = "user-service",
    path = "/user",
    fallbackFactory = UserServiceFallback.class,  // 熔断降级
    configuration = FeignConfig.class              // 自定义配置
)
public interface UserServiceClient {

    @GetMapping("/get")
    User getById(@RequestParam("id") Long id);

    @PostMapping("/save")
    Result save(@RequestBody User user);

    @GetMapping("/list")
    List<User> list(@SpringQueryMap UserQuery query);  // 对象转 QueryString
}
```

---

## 六、Feign 拦截器 + 线程隔离时的坑

```
调用链路：

  REST Controller
       │ Thread-A（有 Token 从请求头来）
       ▼
  UserServiceClient.getById(id)
       │
       ▼
  Feign RequestInterceptor → 取 Token 加到请求头

正常没问题，但用 Sentinel 线程池隔离时：

  主线程 Thread-A → Feign → Sentinel 线程池新起 Thread-B
                                         Thread-B 里取不到主线程 RequestContext 里的东西

解决：改用 SEMAPHORE 信号量隔离，或把 Token 透传写到 InheritableThreadLocal
```

---

## 七、超时与重试

### 7.1 超时配置建议

```
connect-timeout: 3000   # 500ms~1s 就够了，3s 太宽容
read-timeout: 5000      # 业务允许的响应上限
```

### 7.2 重试 —— 慎重

```
❌ 不建议在 Feign 层做重试：
  - 你发起重试 → 下游重复消费 → 重复扣款 / 重复发消息
  - 幂等接口可以（GET），非幂等绝对禁止（POST/PUT/DELETE）

✅ 正确做法：
  - Gett 接口：最多重试 1 次，超过算失败
  - 写接口：绝不重试，上游业务层做补偿
```

---

## 八、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 404 Not Found | 服务名写错 / path 配错 | 检查 @FeignClient name 是否与注册中心一致 |
| 连接超时 | 服务不在线 / 网络不通 | 查 Nacos 控制台是否有健康实例 |
| 读取超时 | 接口响应慢 | 调大 read-timeout 或优化下游 |
| RequestParam 没传过去 | 漏配 @RequestParam | 即使有默认值也写上注解 |
| 首次调用超时 | Feign 懒加载 + Ribbon 预热 | `eager-load.enabled: true` |

---

## 九、面试话术（30 秒版）

> OpenFeign 是声明式 HTTP 客户端，写 Interface + 注解就能调远端，底层 JDK 动态代理组装请求。结合 LoadBalancer 自动从注册中心拉取实例列表做轮询/权重负载均衡。请求拦截器统一加 Token 透传，写接口不建议重试防止重复操作，超时配 connect-timeout 和 read-timeout 两个维度。
