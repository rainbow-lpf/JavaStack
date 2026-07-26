package com.axon.java.stack.dubbo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dubbo 消费者调用全流程 —— 面试标准答案 + 代码模拟
 *
 * <h2>面试回答模板</h2>
 * <pre>
 * 面试官: "Dubbo 消费者调用流程是怎么样的？"
 *
 * 回答:
 * Dubbo 消费者调用，从一句 helloService.sayHello() 开始，底层走 6 步：
 *
 * 第1步 RPC 代理层:
 *   @DubboReference 注入的接口，实际是 Javassist/ByteBuddy 生成的动态代理对象。
 *   方法调用 → InvokerInvocationHandler → 进入 Invoker 链路。
 *
 * 第2步 服务路由:
 *   从注册中心(Nacos/ZK)拉到的 provider 列表，先经 Router 链过滤：
 *   条件路由、Tag 路由、Mesh 路由等，过滤掉不满足条件的节点。
 *
 * 第3步 负载均衡:
 *   从过滤后的 provider 中按 LB 策略选一个：
 *     - Random:      加权随机（默认）
 *     - RoundRobin:  加权轮询
 *     - LeastActive: 最少活跃调用数优先
 *     - ConsistentHash: 相同参数走同一节点
 *
 * 第4步 Filter 链:
 *   消费者侧 Filter 依次执行：监控埋点、隐式传参(RpcContext)、限流令牌、
 *   泛化调用转换、异常处理等，执行完后交给 Protocol 层。
 *
 * 第5步 网络传输:
 *   协议编码(如 Dubbo 协议 Header+Body) → Netty 发送请求。
 *   一个 TCP 连接承载多个并发请求(多路复用)，通过 requestId 唯一匹配响应。
 *   如果失败，依赖 Cluster 容错：failover 自动重试下一个节点(默认2次)。
 *
 * 第6步 Provider 侧 & 结果返回:
 *   Provider: 解码 → 反序列化 → 找到对应 Invoker → 反射调用真实实现类 →
 *   序列化结果 → 写回连接。
 *   消费者收到响应 → 根据 requestId 唤醒阻塞线程(同步) 或 complete Future(异步)。
 *
 * "一句话总结: 代理拦截 → 路由LB选节点 → Filter链 → 序列化发Netty → Provider反射 → 结果返回。"
 * </pre>
 *
 * <h2>核心概念对比</h2>
 * <pre>
 * | 概念             | 作用                                      | 扩展点                 |
 * |-----------------|------------------------------------------|-----------------------|
 * | Proxy           | 透明化远程调用，让远程像本地一样调用            | ProxyFactory          |
 * | Router          | 从 provider 列表过滤可用 RouterFactory         |
 * | LoadBalance     | 从可用节点选一个发起调用                    | LoadBalance SPI       |
 * | Filter          | 调用链路上的拦截处理(监控、限流…)            | Filter SPI            |
 * | Protocol        | 协议编解码、发起网络调用                    | Protocol SPI          |
 * | Cluster         | 容错：失败重试(failover)、快速失败(failfast) | Cluster SPI           |
 * | Transporter     | 底层网络传输(NIO/Netty/Mina)              | Transporter SPI       |
 * | Serialization   | 序列化方式(Hessian2/Fastjson/protobuf)    | Serialization SPI     |
 * | Registry        | 服务注册与发现(Nacos/ZK/Redis)            | RegistryFactory SPI   |
 * </pre>
 *
 * <h2>调用链图示</h2>
 * <pre>
 * 消费者端
 * helloService.sayHello()
 *       │
 *       ▼
 * [1. Proxy代理]    Javassist 动态代理 → InvokerInvocationHandler
 *       │
 *       ▼
 * [2. Router路由]   条件路由 + Tag 路由 → 过滤 provider 列表
 *       │
 *       ▼
 * [3. LoadBalance]  Random / RoundRobin / LeastActive / ConsistentHash
 *       │
 *       ▼
 * [4. Filter链]     MonitorFilter → RateLimitFilter → ...
 *       │
 *       ▼
 * [5. Protocol]     协议编码 → Netty Client 发送请求 (多路复用, requestId)
 *       │
 *       ▼
 * [6. Cluster容错]   failover: 超时 → 重试下一个节点 (默认重试2次)
 *                    failfast: 失败直接抛 RpcException
 *                    failback: 失败后异步补偿定时重试
 *       │
 *       ▼
 * [7. 结果处理]      sync:   线程阻塞等结果
 *                    async:  返回 CompletableFuture
 *                    oneway: 不关心结果, 直接返回
 *       │
 *       ▼                    ╔══════════════════╗
 * [网络传输]  ◀═══════════════╣  Provider 端       ║
 *                             ║  解码 → 反序列化     ║
 *                             ║  → 找到 Invoker      ║
 *                             ║  → 反射调用真实实现类  ║
 *                             ║  → 序列化结果 → 写回  ║
 *                             ╚══════════════════╝
 * </pre>
 */
public class DubboConsumerFlowDemo {

    // ======================== 1. 服务接口 ========================
    public interface HelloService {
        String sayHello(String name);
    }

    // ======================== 2. 负载均衡策略 ========================
    interface LoadBalance {
        String select(List<String> providers, String methodName);
    }

    static class RandomLoadBalance implements LoadBalance {
        private final Random random = new Random();

        @Override
        public String select(List<String> providers, String methodName) {
            return providers.get(random.nextInt(providers.size()));
        }
    }

    static class RoundRobinLoadBalance implements LoadBalance {
        private final AtomicInteger index = new AtomicInteger(0);

        @Override
        public String select(List<String> providers, String methodName) {
            int i = index.getAndIncrement() % providers.size();
            return providers.get(i);
        }
    }

    static class LeastActiveLoadBalance implements LoadBalance {
        private final ConcurrentHashMap<String, AtomicInteger> activeCount = new ConcurrentHashMap<>();

        @Override
        public String select(List<String> providers, String methodName) {
            return providers.stream()
                    .min((a, b) -> {
                        int ca = activeCount.computeIfAbsent(a, k -> new AtomicInteger(0)).get();
                        int cb = activeCount.computeIfAbsent(b, k -> new AtomicInteger(0)).get();
                        return Integer.compare(ca, cb);
                    })
                    .orElse(providers.get(0));
        }

        public void incActive(String provider) {
            activeCount.computeIfAbsent(provider, k -> new AtomicInteger(0)).incrementAndGet();
        }

        public void decActive(String provider) {
            activeCount.get(provider).decrementAndGet();
        }
    }

    // ======================== 3. Filter 链 ========================
    interface Filter {
        Object invoke(Invoker invoker, String methodName, Object[] args) throws Exception;
    }

    interface Invoker {
        Object invoke(String methodName, Object[] args) throws Exception;
    }

    /** 监控 Filter: 记录调用次数和耗时 */
    static class MonitorFilter implements Filter {
        @Override
        public Object invoke(Invoker invoker, String methodName, Object[] args) throws Exception {
            long start = System.currentTimeMillis();
            try {
                System.out.println("[MonitorFilter] 开始调用 " + methodName);
                return invoker.invoke(methodName, args);
            } finally {
                long cost = System.currentTimeMillis() - start;
                System.out.println("[MonitorFilter] 调用耗时: " + cost + "ms");
            }
        }
    }

    /** 限流 Filter */
    static class RateLimitFilter implements Filter {
        @Override
        public Object invoke(Invoker invoker, String methodName, Object[] args) throws Exception {
            System.out.println("[RateLimitFilter] 限流检查通过");
            return invoker.invoke(methodName, args);
        }
    }

    // ======================== 4. 协议层 ========================
    static class RpcProtocol {
        private static final AtomicInteger requestIdGenerator = new AtomicInteger(0);

        static int nextRequestId() {
            return requestIdGenerator.incrementAndGet();
        }

        static byte[] encode(String interfaceName, String methodName, Object[] args) {
            String payload = interfaceName + "|" + methodName + "|" + Arrays.toString(args);
            System.out.println("[协议编码] " + payload);
            return payload.getBytes();
        }

        static Object decode(byte[] response) {
            String result = new String(response);
            System.out.println("[协议解码] " + result);
            return result;
        }
    }

    // ======================== 5. 网络传输层(模拟) ========================
    static class NettyTransport {
        static byte[] send(String provider, byte[] data, int requestId) {
            System.out.println("[Netty] → 发送请求到 " + provider + " requestId=" + requestId);
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            return ("你好 " + new String(data).split("\\|")[2]).getBytes();
        }
    }

    // ======================== 6. 容错策略 ========================
    interface Cluster {
        Object invoke(List<String> providers, String methodName, Object[] args) throws Exception;
    }

    static class FailoverCluster implements Cluster {
        private int retries = 2;

        @Override
        public Object invoke(List<String> providers, String methodName, Object[] args) throws Exception {
            Exception lastException = null;
            for (int i = 0; i <= retries; i++) {
                try {
                    return doInvoke(providers, methodName, args, i);
                } catch (Exception e) {
                    lastException = e;
                    System.out.println("[Failover] 第" + (i + 1) + "次调用失败，重试...");
                }
            }
            throw lastException;
        }

        private Object doInvoke(List<String> providers, String methodName, Object[] args, int retryIndex) {
            LoadBalance lb = new RandomLoadBalance();
            String selected = lb.select(providers, methodName);
            byte[] data = RpcProtocol.encode("HelloService", methodName, args);
            int requestId = RpcProtocol.nextRequestId();
            byte[] response = NettyTransport.send(selected, data, requestId);
            return RpcProtocol.decode(response);
        }
    }

    static class FailfastCluster implements Cluster {
        @Override
        public Object invoke(List<String> providers, String methodName, Object[] args) throws Exception {
            LoadBalance lb = new RandomLoadBalance();
            String selected = lb.select(providers, methodName);
            byte[] data = RpcProtocol.encode("HelloService", methodName, args);
            int requestId = RpcProtocol.nextRequestId();
            byte[] response = NettyTransport.send(selected, data, requestId);
            return RpcProtocol.decode(response);
        }
    }

    // ======================== 7. Proxy 代理工厂 ========================
    static class RpcProxyFactory {
        private final List<String> providers;
        private final Cluster cluster;
        private final List<Filter> filters;

        RpcProxyFactory(List<String> providers, Cluster cluster, List<Filter> filters) {
            this.providers = providers;
            this.cluster = cluster;
            this.filters = filters;
        }

        @SuppressWarnings("unchecked")
        <T> T createProxy(Class<T> interfaceClass) {
            return (T) Proxy.newProxyInstance(
                    interfaceClass.getClassLoader(),
                    new Class[]{interfaceClass},
                    new RpcInvocationHandler()
            );
        }

        class RpcInvocationHandler implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(this, args);
                }

                System.out.println("\n===== RPC 调用开始 =====");
                System.out.println("[代理层] 拦截方法: " + method.getName());

                // 构建 Filter 链
                Invoker lastInvoker = (methodName, methodArgs) -> cluster.invoke(providers, methodName, methodArgs);
                for (int i = filters.size() - 1; i >= 0; i--) {
                    Filter f = filters.get(i);
                    Invoker next = lastInvoker;
                    lastInvoker = (mn, ma) -> f.invoke(next, mn, ma);
                }

                Object result = lastInvoker.invoke(method.getName(), args);
                System.out.println("[结果] " + result);
                System.out.println("===== RPC 调用结束 =====\n");
                return result;
            }
        }
    }

    // ======================== 8. 异步调用演示 ========================
    static class AsyncRpcResult {
        static CompletableFuture<String> asyncCall(String name) {
            return CompletableFuture.supplyAsync(() -> {
                System.out.println("[异步线程] 执行 RPC 调用...");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                return "你好(异步) " + name;
            });
        }
    }

    // ======================== main ========================
    public static void main(String[] args) throws Exception {
        List<String> providers = Arrays.asList(
                "192.168.1.101:20880",
                "192.168.1.102:20880",
                "192.168.1.103:20880"
        );

        System.out.println("========== 1. Failover 容错 + Filter 链 ==========");
        Cluster failoverCluster = new FailoverCluster();
        List<Filter> filters = Arrays.asList(new MonitorFilter(), new RateLimitFilter());
        RpcProxyFactory proxyFactory = new RpcProxyFactory(providers, failoverCluster, filters);

        HelloService helloService = proxyFactory.createProxy(HelloService.class);
        String result = helloService.sayHello("World");
        System.out.println("最终返回: " + result);

        System.out.println("========== 2. 异步调用 ==========");
        CompletableFuture<String> future = AsyncRpcResult.asyncCall("Dubbo");
        future.thenAccept(r -> System.out.println("异步结果: " + r));
        Thread.sleep(200);
    }
}
