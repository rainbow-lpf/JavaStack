# Netty — 面试总结

> 目录：`com.axon.java.stack.netty`

---

## 一、BIO、NIO、Netty 层层递进

### 1.1 BIO（传统阻塞 IO）

```
ServerSocket accept() → 阻塞，等客户端连
连上后 → 每个客户端分配一个线程 → 线程 read() → 阻塞等数据

问题：
  Client-1 → Thread-1（read 阻塞，在等数据）
  Client-2 → Thread-2（read 阻塞，在等数据）
  Client-3 → Thread-3（其实没干活，就在那等着）
  ...
  10 万个客户端 = 10 万个线程，CPU 在上下文切换中死去
```

### 1.2 NIO（非阻塞 IO）

```
三大核心组件：
Buffer — 缓冲区，数据的中转站
Channel — 双向读写
Selector — 多路复用器，一个线程管多个 Channel

Selector：一个线程问 "你们谁有数据？"
  Channel-1：我没有
  Channel-98：我！有数据！
Selector → 通知线程：处理 Channel-98 的，别的不管
→ 一个线程处理 1 万个连接而不开 1 万个线程
```

### 1.3 Netty = 对 NIO 的封装

```
Netty 说：你直接用 NIO 的 Buffer、Channel、Selector API 写代码太累了。
我把这些封装起来，给你一个开箱即用的高性能网络框架。

Dubbo 底层用的就是 Netty。
```

---

## 二、Reactor 模式 — Netty 的线程模型

### 2.1 酒店入住比喻

```
1. 传统 IO（BIO）：
   每个接待员一次只接待一个客人，干完所有活才接下一个。
   → 接待员有限，多余的客人直接拒绝。客人大规模流失。

2. 单 Reactor 单线程：
   一个接待员同时接待、登记、带客房。
   → 人多就排队，一人扛所有，效率低。

3. 单 Reactor 多线程：
   一个接待员接客 → 交给多个前台经理（线程池）办理入住。
   → 接待员只接客不下活。但接待员一个，爆发高扛不住。

4. 主从 Reactor 多线程（Netty 用的！）：
   多个接待员 → 多个前台经理 → 多个服务员。
   分工明确，弹性扩展。
```

### 2.2 Netty 实际模型

```
BossGroup（主 Reactor，多个 Boss NioEventLoop）
    │
    │  负责：接受客户端 TCP 连接
    │
    ▼
WorkGroup（从 Reactor，多个 Worker NioEventLoop）
    │
    │  负责：读数据 → 解码 → 业务处理 → 编码 → 写回
    │
    ▼
每个 Channel 绑定一个 Worker NioEventLoop
一个 NioEventLoop 可以管 三、Netty 核心组件

### 3.1 组件全景

```
Channel         → 一条 TCP 连接的抽象
ChannelPipeline → 事件处理流水线（存放所有 Handler）
ChannelHandler  → 处理入站/出站数据的处理器
ChannelHandlerContext → Handler 与 Pipeline 之间的上下文
EventLoop       → 事件循环线程，一条 Channel 一生只绑定一个 EventLoop
ByteBuf         → Netty 的数据容器（类比 NIO 的 Buffer）
```

### 3.2 Pipeline 调用链

```
客户端消息 → Socket → Channel → ChannelPipeline
                                    │
            HeadHandler ← Layer1 ← Layer2 ← Layer3 ← TailHandler
            (入站)      (出站)
                │                    │              │
                ▼                    ▼              ▼
            解码器              业务Handler      编码器

入站方向：Head → Decoder → BizHandler → 内容 → Tail
出站方向：Tail → Encoder → ... → Head
```

---

## 四、Netty 任务队列

三种异步任务：

| 类型 | 说明 | 场景 |
|------|------|------|
| **普通任务** | `ctx.channel().eventLoop().execute(() -> {...})` | 耗时操作异步化 |
| **定时任务** | `ctx.channel().eventLoop().schedule(() -> {...}, 5, SECONDS)` | 心跳检测、超时控制 |
| **跨线程操作** | 别的线程往 Channel 写数据 | 推送系统根据用户 ID 找 Channel，往里面写 |

---

## 五、Future / Listener 异步模型

```java
ChannelFuture future = serverBootstrap.bind(9000).addListener(f -> {
    if (f.isSuccess()) {
        System.out.println("绑定成功");
    } else {
        System.out.println("绑定失败");
    }
});
// 不会阻塞主线程，绑定完成后通知 → 回调执行
```

| 方法 | 含义 |
|------|------|
| `isDone()` | 操作是否完成 |
| `isSuccess()` | 操作是否成功 |
| `getCause()` | 失败原因 |
| `addListener()` | 注册回调监听器，完成时自动调用 |

---

## 六、零拷贝

```
传统 IO 发送文件：
  磁盘 → 内核缓冲区 → 用户态 buffer → 内核网络缓冲区 → 网卡
  （4 次拷贝，2 次上下文切换）

Netty 零拷贝（sendfile）：
  磁盘 → 内核缓冲区 → 网卡
  （跳过用户态，2 次拷贝）
```

| Netty 零拷贝手段 | 说明 |
|------|------|
| **FileRegion** | 调 `sendfile()` 直传文件 |
| **CompositeByteBuf** | 多个 ByteBuf 逻辑拼接，不复制 |
| **DirectByteBuf** | 堆外内存，不在 JVM 堆里倒腾 |
| **Unpooled.wrappedBuffer** | 直接包装已有数组，不复制 |

---

## 七、TCP 粘包 / 拆包 & 编解码器

### 7.1 粘包拆包问题

```
TCP 是流式协议，传输的是字节流，没有消息边界。

发送方发了 3 条消息：A、B、C
接收方可能收到：
  A+B 粘包：两条消息粘一起，你分不开哪是 A 哪是 B
  A+半条B：拆包，B 被砍成两半
```

### 7.2 解决方案

| 方案 | 说明 |
|------|------|
| **固定长度** | 每条消息固定 N 字节，截齐了读 |
| **分隔符** | 每条消息以 `\n` 等分隔符结束 |
| **消息头+消息体** | 头里带长度字段（4 字节长度 + 数据体），读了头就知道该读多少 |

```java
// Netty 的解决方案：自己封包协议 + 编解码器
消息格式：[长度(4字节)][数据体(N字节)]

// 编码器：发送时把数据打包
// 解码器：接收时根据长度拆包
```

### 7.3 编解码器

| 组件 | 作用 | 案例 |
|------|------|------|
| **Decoder**（解码器） | 入站：字节流 → Java 对象 | `ByteToLongDecoder`、`ReplayingDecoder` |
| **Encoder**（编码器） | 出站：Java 对象 → 字节流 | `ByteToLongEncoder` |

```
Client 发送：Long 123 → Encoder → 字节 → TCP → 字节 → Decoder → Long 123 → Server
```

---

## 八、Netty 支持的协议 & 扩展

| 能力 | 案例代码 | 场景 |
|------|------|------|
| **自定义 TCP 协议** | `NettyServerDemo` | 内部 RPC 调用 |
| **WebSocket** | `WebSocketServer` | 实时推送 |
| **HTTP** | `HttpServer` | HTTP 服务，如 Rest API |
| **Protobuf** | `ProtobufServer` | Google 序列化，高性能 |
| **心跳检测** | `HeartbeatServer` | 服务间探活 |
| **聊天室** | `ChatGroupServer` | IM 通信 |

---

## 九、面试题

### Q1：Netty 的线程模型说清楚。

> BossGroup 负责接收连接，注册到 WorkGroup。WorkGroup 的 NioEventLoop 负责真正的 IO 读写。每个 Channel 绑定一个 EventLoop，ChannelPipeline 链式处理（Decoder → BizHandler → Encoder）。

### Q2：BIO、NIO、Netty 的区别是什么？

> BIO 一个连接一个线程，阻塞式，线程数 = 连接数 → 爆炸。NIO Selector 多路复用器 + Channel + Buffer，一个线程管多个连接。Netty 是对 NIO 的封装，用 Reactor 模式，零拷贝，Pipeline 链式处理，TCP 粘包拆包解决方案全内置。

### Q3：什么是零拷贝？

> Netty 的零拷贝不是"完全没有拷贝"，是减少用户态和内核态间的数据拷贝。`sendfile()` 跳过用户态直接传文件，`DirectByteBuf` 堆外内存不倒，`CompositeByteBuf` 多块拼接不复制。

### Q4：Pipeline 是怎么工作的？

> ChannelPipeline 是一条 Handler 链表。入站消息从 Head → 依次经过 Decoder → BizHandler → ... → Tail。出站消息从 Tail → 依次经过 Encoder → ... → Head。

### Q5：TCP 粘包怎么解决？

> 固定长度、分隔符、自定义协议（头+体）。Netty 用自定义协议：4 字节长度 + 数据体，编码器打包/解码器拆包。

### Q6：Reactor 有哪三种模式？Netty 用的哪种？

> 单 Reactor 单线程（一把梭）、单 Reactor 多线程（一个接客多个办）、主从 Reactor 多线程（多个接客+多个办+多个服）。Netty 用的主从 Reactor，高并发之王。

### Q7：Future/Listener 机制怎么理解？

> 异步操作返回 ChannelFuture。绑定端口、写数据等操作不会阻塞线程。可以往 future 上绑 Listener，操作完成后自动回调通知。

### Q8：Netty 适合哪些场景？

> Dubbo、gRPC 底层通信 = Netty。WebSocket 推送、HTTP 服务、IM 聊天室、心跳探活、文件传输。任何高性能 TCP/UDP 网络应用。
