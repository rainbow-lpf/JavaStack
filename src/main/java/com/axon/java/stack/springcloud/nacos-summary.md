# 服务注册与发现（Nacos / Eureka）

> 目录：`com.axon.java.stack.springcloud`

---

## 一、一句话说清楚

> 微服务实例地址是动态变化的（扩容/缩容/宕机），注册中心是一个"通讯录"，服务启动时登记自己的地址，调用方发现目标地址时去通讯录查。

---

## 二、核心概念

```
服务提供方 (Provider)           注册中心 (Registry)           服务消费方 (Consumer)
     │                              │                              │
     ├─ 启动时 → 注册（POST /register）                              │
     │                              │                              │
     │                              ├─ 服务列表（User-Service: 三个实例）
     │                              │   ├ 192.168.1.10:8081        │
     │                              │   ├ 192.168.1.11:8081        │
     │                              │   └ 192.168.1.12:8081        │
     │                              │                              │
     │                              │        ← 服务发现（GET /instances）
     │                              │                              │
     ├─ 每 5s 发一次心跳（续约）────→│
     │                              │                              │
     ├─ 关机时 → 注销（deregister）  │                              │
```

### 关键机制

| 机制 | 说明 |
|------|------|
| **注册** | 服务启动时向注册中心登记 IP + 端口 + 元数据 |
| **发现** | 调用方从注册中心拉取服务列表，本地缓存 |
| **心跳** | 服务定期发心跳证明自己还活着 |
| **剔除** | 心跳超时未续约 → 注册中心摘除该实例 |
| **健康检查** | 主动/被动探活，不健康的不分发 |

---

## 三、Nacos vs Eureka

| 维度 | Nacos | Eureka |
|------|-------|--------|
| **开发方** | 阿里巴巴 | Netflix（已停更 2.0 后） |
| **一致性模型** | AP / CP 可切换 | AP（最终一致） |
| **CAP 取舍** | `--` 配置下可以切 CP（Raft） | 坚定 AP（自我保护机制） |
| **服务下线感知** | 快（主动通知 + 心跳） | 慢（依赖心跳 + 自我保护拉长） |
| **控制台** | ✅ 自带可视化管理 | ✅ 自带界面 |
| **配置中心** | ✅ 内置配置管理 | ❌ 需配合 Spring Cloud Config |
| **健康检查** | TCP / MySQL / 自定义三种模式 | 仅心跳 |
| **多数据中心** | ✅ 支持 | ✅ 支持 |
| **集群模式** | Raft 选主 + 节点同步 | Peer-to-Peer 复制 |

---

## 四、CAP 理论在注册中心的应用

```
CAP 三者只能同时满足两个：

  C（一致性）：所有节点同一时刻数据一致
  A（可用性）：集群任何时候都能响应请求
  P（分区容错）：网络发生分区时集群仍能工作

  Eureka → AP（舍弃一致性）：
    网络分区时宁可留过期数据，也要让服务活着
    自我保护机制：宁可留着所有实例也不剔掉，保证可用

  Nacos（CP 模式）→ CP（舍弃可用性）：
    网络分区时宁可拒绝写，也要保证数据一致
    主从选举期间不可写

  Nacos（AP 模式）→ AP：
    和 Eureka 一样，优先可用
```

### 面试话术

> Eureka 是 AP 模型，网络抖动时触发自我保护，保留过期实例保证可用性；Nacos 支持 AP/CP 切换，默认 AP，配置集群时可用 Raft 协议切 CP 保证一致性。

---

## 五、Nacos 核心原理

### 5.1 注册表结构（双层 Map）

```
Map<namespace, Map<group::serviceName, Service>>

Service 内部：
  ├─ clusterMap（集群 → 实例列表）
  ├─ instances（当前所有实例）
  └─ consistencyService（一致性协议实现：AP 用 Distro，CP 用 Raft）
```

### 5.2 临时实例 vs 持久实例

| | 临时实例（ephemeral=true，默认） | 持久实例（ephemeral=false） |
|--|-------------------------------|--------------------------|
| 一致性协议 | AP（Distro） | CP（Raft） |
| 健康检查 | 客户端主动心跳，Nacos 被动接收 | Nacos 主动探测服务端 |
| 宕机处理 | 心跳超时自动摘除 | 标记不健康，不自动删除 |
| 适用场景 | 微服务普通业务节点 | 基础设施（中间件/数据库/关） |

### 5.3 服务发现流程（AP 模型）

```
1. Provider 启动 → 向 Nacos 注册（REST /api/register）
2. Consumer 启动 → 向 Nacos 拉取服务列表 + 订阅（长轮询/UDP 推送）
3. Consumer 本地缓存服务列表 + 定时更新（30s）
4. Provider 每 5s 发心跳续约
5. Nacos 15s 没收到心跳 → 标记不健康 → 30s 后剔除
6. Nacos 推送变更通知给订阅的 Consumer
```

### 5.4 Nacos 集群架构

```
    [SLB / VIP]
         │
    ┌────┼────┐
    ▼    ▼    ▼
 Nacos-1  Nacos-2  Nacos-3   ← 三节点集群
  Leader  Follower Follower
    │      │       │
    └──────┴───────┘
    数据同步（Raft 协议）
```

---

## 六、服务发现负载均衡

服务发现只是拿到地址列表，具体**选哪一个**由负载均衡决定：

```
Consumer 请求 Order-Service
   │
   ▼
DiscoveryClient → 拉取 Order-Service 实例列表（3 个）
   │
   ▼
LoadBalancer → 按策略选一个（轮询/随机/权重）
   │
   ▼
OpenFeign → 发起 HTTP 调用具体实例
```

---

## 七、关键配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: dev            # 隔离环境
        group: DEFAULT_GROUP
        ephemeral: true           # 临时实例（AP）
        heart-beat-interval: 5000 # 心跳间隔 ms
        heart-beat-timeout: 15000 # 心跳超时 ms
```

---

## 八、常见问题

| 问题 | 原因 | 解決 |
|------|------|------|
| 启动报连不上 Nacos | 网络不通/端口被防火墙挡 | 检查 8848 |
| Consumer 拿不到服务列表 | namespace / group 配置不一致 | namespace 为空表示 public，group 默认 DEFAULT_GROUP |
| 服务下线后 Consumer 仍调老 IP | 本地缓存没刷新 | 等一个刷新周期（30s）或重启 |
| Nacos 集群脑裂 | 网络分区导致多 Leader | 减少 RTO，配置奇数节点（3 台） |
| Eureka 自我保护警告 | 心跳大面积丢失 | 检查网络，不用直接关（宁可保留也不误杀） |

---

## 九、面试话术（30 秒版）

> 注册中心是微服务的通讯录。Provider 启动时注册，Consumer 发现时拉取 + 本地缓存。Nacos 支持 AP/CP 切换：临时实例用 Distro 协议做最终一致，持久实例用 Raft 协议做强一致。Eureka 是纯 AP，有自我保护机制，宁可留过期数据也要保证可用性。
