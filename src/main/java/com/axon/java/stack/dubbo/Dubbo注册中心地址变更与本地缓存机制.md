> **总结日期：** 2026-07-07
> **核心主题：** Dubbo 注册中心地址变更时本地缓存的更新与兜底机制

---

## 一、核心问题

> 注册中心里的 Provider 地址改掉了，Consumer 本地缓存的旧地址怎么办？

---

## 二、Dubbo 三层地址存储

| 层级 | 存储位置 | 作用 | 更新方式 |
|---|---|---|---|
| **注册中心** | Nacos / Zookeeper / Redis | 权威数据源 | Provider 心跳上报 |
| **内存地址列表** | Consumer JVM 内存 | RPC 调用直接使用 | NotifyListener 实时回调 |
| **本地文件缓存** | `~/.dubbo/` 目录 | 注册中心挂了时兜底 | notify 后异步写入 |

---

## 三、完整流转过程

### 场景 A：注册中心正常，Provider 地址变更

```
Provider 下线 (IP 变更 / 服务停止)
    │
    ▼
注册中心检测到 Provider 断开
  (心跳超时 / 主动注销)
    │
    ▼
注册中心 push 通知 / Consumer 长轮询
  (Nacos: UDP push + 长轮询拉取 / ZK: Watcher 回调)
    │
    ▼
Consumer RegistryDirectory.notify()
    │
    ├──► 更新内存中的 Invoker 列表 (下次 RPC 调用即时生效)
    │
    └──► 异步写 ~/.dubbo/xxx.cache (文件缓存同步落盘)
```

**结论：** 注册中心正常时，本地缓存**实时更新**，不存在不一致问题。

---

### 场景 B：注册中心挂了，Consumer 还没重启

```
注册中心挂了 ✗
    │
    ▼
Consumer 收不到新的 notify
    │
    ▼
内存中保留最后一次拿到的 Provider 列表
    │
    ▼
RPC 调用继续走内存列表 → 正常工作
```

**结论：** 注册中心宕机，Consumer 不停服务。内存中的列表就是"当前缓存"。

---

### 场景 C：注册中心挂了，Consumer 也重启了

```
注册中心挂了 ✗
Consumer 重启
    │
    ▼
尝试连注册中心 → 失败
    │
    ▼
读本地文件缓存: ~/.dubbo/dubbo-registry-xxx.cache
    │
    ▼
加载文件中的 Provider 列表到内存 → 继续工作
```

**结论：** 文件缓存是**最后的兜底**，保证 Consumer 重启后还能拿到 Provider 地址。

---

## 四、本地文件缓存细节

### 文件位置

```
{用户目录}/.dubbo/
  └── dubbo-registry-{applicationName}-{registryAddress}.cache
```

### 文件内容示例

```properties
# 存的是 interface 和 provider 地址列表的映射
com.axon.service.HelloService=192.168.1.101:20880,192.168.1.102:20880
com.axon.service.OrderService=192.168.1.103:20880
```

### 更新时机

| 触发条件 | 动作 |
|---|---|
| Consumer 启动 | 读文件 → 加载到内存（注册中心不可用时） |
| NotifyListener 收到变更 | 异步写文件（同步内存，异步落盘） |
| Consumer 正常关闭 | 写文件（ShutdownHook） |

---

## 五、地址变更场景汇总

| 场景 | 注册中心 | Consumer | Consumer 重启 | 结果 |
|---|---|---|---|---|
| 日常变更 | 正常 | 运行中 | - | notify → 内存实时更新 → 异步写文件 |
| 注册中心宕机 | **挂了** | 运行中 | - | 内存旧列表继续用，服务不中断 |
| 注册中心宕机 + Consumer 重启 | **挂了** | 重启 | ✓ | 读本地文件缓存恢复地址列表 |
| 地址变更后注册中心宕机 + Consumer 重启 | **挂了** | 重启 | ✓ | 文件缓存在变更前已更新 → 新地址生效 |
| 地址变更前注册中心宕机 + Consumer 重启 | **挂了** | 重启 | ✓ | 文件缓存未来得及更新 → 旧地址（调用失败、触发容错） |

---

## 六、面试回答模板

> 面试官："注册中心地址变了，本地缓存怎么处理？"
>
> 回答：
> "Dubbo 有三层存储：注册中心、Consumer 内存、本地文件缓存。
>
> 第一层，注册中心是权威源，Provider 上下线会实时更新。
> 第二层，注册中心通过 notify 机制 push 到 Consumer，Consumer 在 NotifyListener 回调中实时更新内存里的 Invoker 列表，下次 RPC 调用即时生效。
> 第三层，同时异步写 `~/.dubbo/` 下的本地文件缓存，作为注册中心不可用时的兜底。
>
情况：
> 1. 注册中心挂了 Consumer 没重启——用内存里的列表继续调，不受影响
> 2. 注册中心挂了 Consumer 也重启——读本地文件缓存恢复地址列表，业务不中断
> 3. 地址刚变注册中心就挂了 Consumer 又重启——文件缓存如果在 notify 后已经更新过就是新的，如果还没来得及更新就是旧的，这时调用失败会触发 failover 重试其他节点
>
> 一句话：notify 保证实时一致性，文件缓存保证高可用兜底。"

---

## 七、关键源码类（拓展）

| 类 | 作用 |
|---|---|
| `RegistryDirectory` | 管理 Provider 地址列表，接收 notify 回调 |
| `AbstractRegistry` | 注册中心抽象，实现文件缓存的读写 |
| `ZookeeperRegistry` | ZK 实现，Watcher 监听节点变化 |
| `NacosRegistry` | Nacos 实现，subscribe 监听服务变更 |
| `NotifyListener` | 回调接口，Provider 变更时触发 |
| `FailbackRegistry` | 注册中心挂了自动重连，期间用文件缓存 |
