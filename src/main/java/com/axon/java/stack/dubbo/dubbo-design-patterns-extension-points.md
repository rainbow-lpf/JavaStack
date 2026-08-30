# Dubbo 设计模式与扩展点（SPI）

> 面试三连：源码里用了哪些设计模式？SPI 扩展点机制是什么？项目中真实扩展过什么？

---

## 一、设计模式清单（源码位置版）

| 模式 | Dubbo 中的位置 | 一句话说明 |
|------|---------------|-----------|
| **微内核 + 插件（SPI）** | `ExtensionLoader` 全家 | 整个 Dubbo 的骨架——几乎所有组件都是扩展点 |
| **工厂模式** | `ExtensionFactory`（`SpiExtensionFactory` / `SpringExtensionFactory`） | 决定从 SPI 容器还是 Spring 容器拿依赖 |
| **代理模式** | 消费端 `InvokerInvocationHandler`；服务暴露端 `JavassistProxyFactory` / `JdkProxyFactory` | 接口调用 → Invoker 链 |
| **装饰器（Wrapper）** ★ | `ProtocolFilterWrapper` / `ProtocolListenerWrapper` / `QosProtocolWrapper` 层层包 Protocol | **Dubbo SPI 的 AOP** |
| **责任链** ★ | `ProtocolFilterWrapper#buildInvokerChain`：Filter 链 | EchoFilter→ContextFilter→TimeoutFilter→MonitorFilter→…→业务实现 |
| **策略模式** ★ | 负载均衡 `LoadBalance`：Random / RoundRobin / LeastActive / **ConsistentHash** | URL 参数决定选哪个策略 |
| **策略模式（容错）** | `Cluster`：Failover / Failfast / Failsafe / Failback / Forking / Broadcast | 失败了怎么办的六种策略 |
| **模板方法** | `AbstractClusterInvoker#invoke()` 骨架，`doInvoke()` 留给子类 | 列出 invokers、选负载均衡，子类只管容错逻辑 |
| **观察者模式** | `NotifyListener#notify`；Directory 订阅注册中心变化，provider 上下线自动推送刷新 | 注册中心变更 → 本地 invoker 列表刷新 |
| **适配器（动态生成）** | `@Adaptive` 自适应扩展：`AdaptiveExtensionFactory`、`$Adaptive` 动态编译类 | 按 URL 参数在运行时"适配"到具体实现 |
| **单例（缓存实例）** | `ExtensionLoader#EXTENSION_INSTANCES` | 扩展实例全局缓存复用 |

### 重点展开 3 个（面试官必追问）

#### 1. Filter 责任链——服务调用的"安检通道"

```
Consumer 端（ProtocolFilterWrapper 包 Protocol refer 时构建）：
  调用 → EchoFilter → ConsumerContextFilter → FutureFilter → MonitorFilter → Invoker(网络)

Provider 端（export 时构建）：
  请求 → ContextFilter → ExceptionFilter → TimeoutFilter → TpsLimitFilter … → 业务实现类 Invoker

实现：每个 Filter wrap 一个 Invoker（FilterNode），invoke() 里先做自己的事再 chain.invoke(next)
→ 和 Servlet Filter、Spring Interceptor 同款思想，但对象是 Invoker
```

#### 2. 装饰器——SPI 自带 AOP

```
ExtensionLoader#getAdaptiveExtension（如 Protocol）：
  url=registry://…  → QosProtocolWrapper 包装
  url=dubbo://…     → ProtocolListenerWrapper → ProtocolFilterWrapper → DubboProtocol
包装条件（源码规则）：
  该扩展类只有一个【入参为 SPI 接口】的构造器（拷贝构造）→ 自动识别为 Wrapper
  → 创建实例时 new Wrapper(被包者) 层层嵌套 → 不改代码给所有实现叠加行为
```

#### 3. 观察者——注册中心驱动的动态感知

```
RegistryDirectory implements NotifyListener
  → subscribe(provider 服务) 订阅 /dubbo/服务/providers 目录
  → Provider 上下线 → ZK 节点变化 → 推送 notify(urls) → Directory 缓存的 invoker 列表刷新
  → 调用方无感拿到最新服务列表（这就是动态扩缩容的原理）
```

---

## 二、Dubbo SPI：核心扩展机制（必考中的必考）

### JDK SPI vs Dubbo SPI

| | JDK SPI（ServiceLoader） | Dubbo SPI（ExtensionLoader） |
|---|-------------------------|------------------------------|
| 配置 | `META-INF/services/接口全名`（一行一个实现全类名） | `META-INF/dubbo/接口全名`：`key=实现类` |
| 加载 | **全量实例化**，没法按名取 | **按 key 懒加载**，用哪个实例化哪个 |
| IOC | ❌ | ✅ 扩展点 setter 自动注入其他扩展点（经 ExtensionFactory：先 SPI 容器后 Spring 容器） |
| AOP | ❌ | ✅ Wrapper 自动包装 |
| 自适应 | ❌ | ✅ @Adaptive 按 URL 参数动态选实现 |
| 条件激活 | ❌ | ✅ @Activate 按 group/value 自动装配 |

### 三个关键注解

```java
@SPI("dubbo")          // 标在接口上：默认实现 key
public interface Protocol { ... }

@Adaptive              // 两种用法：
                       // ① 标在方法上：运行时按 URL 里 protocol 参数动态选实现
                       //    （生成 $Adaptive 类 → javassist 编译 → 反射调用）
                       // ② 标在类上：该类直接作为自适应实现（如 AdaptiveExtensionFactory）

@Activate(group = {CONSUMER, PROVIDER}, order = -10000)  // Filter 条件自动激活
public class ContextFilter implements Filter { ... }
```

**@Adaptive 原理一句话：Dubbo 为接口动态生成一个"根据 URL 参数选择实现"的代理类，编译后缓存——把 if-else 写进了生成的代码里。**

### 常用 SPI 接口盘点

```
Protocol（dubbo:// injvm:// registry://）｜ ProxyFactory（javassist/jdk）
LoadBalance｜ Cluster（容错六策略）｜ Filter｜ RegistryFactory/Registry
Serialization（hessian2/kryo/fastjson2）｜ Compiler（javassist/jdk）
Container｜ LoggerAdapter｜ TelnetHandler
```

---

## 三、项目中的真实扩展场景（举 2 个，代码级）

### 场景 1：自定义 Filter 做统一鉴权 + 耗时监控（最常用）

Provider 端加一个登录态校验 + 慢调用日志的 Filter：

```java
@Activate(group = CommonConstants.PROVIDER)
public class AuthProviderFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        String token = inv.getAttachment("auth-token");
        if (!AuthService.check(token)) {
            return AsyncRpcResult.newDefaultAsyncResult(
                new RpcException(401, "unauthorized"), inv);
        }
        long start = System.currentTimeMillis();
        try {
            return invoker.invoke(inv);
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost > 500) {
                log.warn("slow rpc [{}ms] {}#{}", cost,
                         invoker.getInterface().getSimpleName(), inv.getMethodName());
            }
        }
    }
}
```

注册（classpath 下 `META-INF/dubbo/org.apache.dubbo.rpc.Filter`）：

```properties
authProvider=com.xxx.dubbo.filter.AuthProviderFilter
```

生效方式：`@Activate(group=PROVIDER)` 自动激活，或配置 `dubbo.provider.filter=authProvider`。

### 场景 2：自定义负载均衡——同机房优先（灰度/多机房场景）

```java
public class SameSiteLoadBalance extends AbstractLoadBalance {
    @Override
    protected Invoker<?> doSelect(List<Invoker<?>> invokers, URL url, Invocation inv) {
        String localSite = System.getProperty("site.id");
        List<Invoker<?>> same = invokers.stream()
                .filter(i -> localSite.equals(i.getUrl().getParameter("site")))
                .collect(Collectors.toList());
        // 同机房有节点就走本机房，否则退化为随机
        return (same.isEmpty() ? invokers : same)
                .get(ThreadLocalRandom.current().nextInt(...));
    }
}
```

注册 `META-INF/dubbo/org.apache.dubbo.rpc.cluster.LoadBalance` → `sameSite=...`，配置 `loadbalance=sameSite` 即生效。

### 场景 3（备选）：自定义 Cluster 容错

"同机房优先重试"——继承 `AbstractClusterInvoker` 实现 `doInvoke`，参考 `FailoverClusterInvoker` 加机房过滤逻辑。

### 场景 4（备选）：对接自研注册中心

实现 `RegistryFactory` + `Registry` + `NotifyListener`（本质是观察者模式落地）。

---

## 四、面试总结话术

> Dubbo 是"微内核 + 插件"架构，设计模式几乎都围着 SPI 转：ExtensionLoader 是工厂 + 单例缓存；负载均衡四策略、集群容错六策略是策略模式，由 AbstractClusterInvoker 模板方法调度；Protocol 被 Qos/Listener/Filter 三个 Wrapper 装饰器层层包装——这就是 Dubbo SPI 的 AOP；Filter 责任链在两端各拉一条安检链。扩展点上，Dubbo SPI 比 JDK SPI 强在：按 key 懒加载、setter 注入 IOC、Wrapper AOP、@Adaptive 按 URL 动态选实现（动态生成 $Adaptive 类）、@Activate 条件激活。真实项目里我写过 Provider 端鉴权+慢调用 Filter（@Activate 自动激活）、多机房同 Site 优先的负载均衡，配置就是一个 key=类的 SPI 文件 + 一行 URL 参数。

---

## 附：交叉引用（已有文档）

| 主题 | 文档 |
|------|------|
| Dubbo 架构总览 | `dubbo/dubbo-summary.md` |
| CAP 与注册中心选型 | `dubbo/CAP理论与Dubbo注册中心选型.md` |
| 注册中心地址变更机制 | `dubbo/Dubbo注册中心地址变更与本地缓存机制.md` |

---

## 五、考前速记卡

### 必背 10 条（⭐⭐⭐）

1. Dubbo = **微内核 + 插件**：一切组件都是 `@SPI` 扩展点，`ExtensionLoader` 按 key 懒加载
2. JDK SPI vs Dubbo SPI 一句话：**JDK 全量加载、按名取不了；Dubbo 按 key 懒加载 + IOC + AOP + 自适应**
3. `@Adaptive` = **按 URL 参数运行时选实现**：动态生成 `$Adaptive` 类 → javassist 编译 → 缓存
4. `@Activate(group=..., order=...)` = **Filter 条件自动激活**（provider/consumer 两条链）
5. Wrapper 装饰器 = **Dubbo SPI 的 AOP**：类只有一个接口入参构造器 → 自动识别为包装类
6. 负载均衡四策略：Random / RoundRobin / LeastActive / ConsistentHash（一致性哈希解决会话粘滞）
7. 容错六策略：Failover(默认) / Failfast / Failsafe / Failback / Forking / Broadcast
8. 服务引用 = **观察者**：`RegistryDirectory` 订阅 ZK 目录，Provider 上下线 → notify → invoker 列表刷新
9. 服务暴露 = **代理**：`JavassistProxyFactory` 生成实现类包装 Invoker；消费端 `InvokerInvocationHandler`
10. Filter 链两端都有：Consumer（Echo/Context/Monitor…）→ Provider（Context/Exception/Timeout…）

### 追问预判（面试官下一句 → 一句话回）

| 追问 | 一句话答案 |
|------|-----------|
| Dubbo 服务调用全流程？ | 代理 → Cluster（容错）→ LoadBalance（选一个）→ Filter 链 → 网络 → Provider 反着走一遍 |
| 服务暴露全流程？ | ProxyFactory 包 Invoker → Protocol.export → Registry 注册 URL → 本地起服务 |
| 为什么有 wrapper 机制？ | 不改源码给所有 Protocol/扩展叠加监控、QoS、Filter 装配 |
| 优雅上下线怎么实现？ | 下线先反注册（ZK 摘除节点）再停服务，上游 Directory 收到通知剔除 invoker |
| 一致性哈希负载均衡解决啥？ | 服务扩容缩容时尽量让同一请求落到同一节点，减少缓存失效 |

### 记忆权重

- ⭐⭐⭐ 必背：1、2、3、4、6（SPI + 自适应 + 负载均衡，最高频）
- ⭐⭐ 理解：5、8、10（能画出调用链更佳）
- ⭐ 了解：7（记住 Failover/Failfast 即可，其余点到为止）
