# JVM 参数 & 内存结构 — 面试总结

> 目录：`com.axon.java.stack.juc.txt`

---

## 一、JVM 核心参数分类

### 1.1 堆内存参数（高频必问）

| 参数 | 含义 | 示例 |
|------|------|------|
| `-Xms` | 初始堆大小 | `-Xms512m` |
| `-Xmx` | 最大堆大小 | `-Xmx1g` |
| `-Xmn` | 年轻代大小（含 Eden + 2 个 Survivor） | `-Xmn300m` |
| `-Xss` | 每个线程栈大小 | `-Xss512k` |
| `-XX:NewSize` / `-XX:MaxNewSize` | 年轻代初始/最大大小 | `-XX:NewSize=300m` |
| `-XX:OldSize` | 老年代初始大小 | `-XX:OldSize=222m` |
| `-XX:MaxTenuringThreshold` | 晋升老年代年龄上限 | `-XX:MaxTenuringThreshold=6`（最大 15） |

> **关键：** `-Xmn` = Eden + S0 + S1。默认比例 8:1:1，Eden 占年轻代的 80%。

---

### 1.1.1 年轻代与老年代比例

**默认：`-XX:NewRatio=2`**

含义：老年代 = 年轻代 × 2。

```
堆 = 年轻代 + 老年代 = 1 + 2 = 3 份
年轻代 = 堆的 1/3
老年代 = 堆的 2/3
```

**年轻代内部：`-XX:SurvivorRatio=8`**

含义：Eden = 单个 Survivor × 8。

```
年轻代内部：
┌────────────┬──────┬──────┐
│   Eden     │  S0  │  S1  │
│    80%     │ 10%  │ 10%  │
└────────────┴──────┴──────┘
```

**示例：堆 = 3G**

```
堆 = 3G
  ├─ 年轻代 = 1G  (1/3)
  │    ├─ Eden  = 800M  (80%)
  │    ├─ S0    = 100M  (10%)
  │    └─ S1    = 100M  (10%)
  └─ 老年代 = 2G  (2/3)
```

| 参数 | 含义 | 默认值 |
|------|------|:---:|
| `-XX:NewRatio` | 老年代 / 年轻代 | 2 |
| `-XX:SurvivorRatio` | Eden / 单个 Survivor | 8 |
| `-Xmn` | 年轻代绝对值（优先级最高） | 无 |

> 设置 `-Xmn` 会覆盖 `-XX:NewRatio`。

---

### 1.2 GC 参数（CMS 经典配置）

| 参数 | 含义 |
|------|------|
| `-XX:+UseConcMarkSweepGC` | 老年代使用 CMS 回收器 |
| `-XX:+UseParNewGC` | 年轻代使用 ParNew（多线程 Serial） |
| `-XX:+CMSParallelRemarkEnabled` | CMS 重标记阶段并行化，减 STW |
| `-XX:+UseCMSCompactAtFullCollection` | Full GC 时压缩碎片 |
| `-XX:+UseCMSInitiatingOccupancyOnly` | 仅当堆使用达阈值才启动 CMS |
| `-XX:+ExplicitGCInvokesConcurrent` | `System.gc()` 走 CMS 并发（不 Full GC） |

---

### 1.3 OOM 诊断参数

| 参数 | 含义 |
|------|------|
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动 dump 堆快照 |
| `-XX:HeapDumpPath=<path>` | dump 文件路径 |
| `-XX:ErrorFile=<path>` | 致命错误日志路径 |
| `-XX:-OmitStackTraceInFastThrow` | 快速抛出不省略堆栈（排查用） |

---

### 1.4 GC 日志参数

| 参数 | 含义 |
|------|------|
| `-XX:+PrintGCDetails` | 打印 GC 详细日志 |
| `-XX:+PrintGCDateStamps` | 日志带日期时间戳 |
| `-XX:+PrintGCTimeStamps` | 日志带相对时间戳 |
| `-Xloggc:<path>` | 指定 GC 日志输出文件路径 |

---

### 1.5 指针压缩 & 大页

| 参数 | 含义 |
|------|------|
| `-XX:+UseCompressedOops` | 普通对象指针压缩（64 位→32 位），省内存 |
| `-XX:+UseCompressedClassPointers` | 类元数据指针压缩，前提是开了 CompressedOops |
| `-XX:LargePageSizeInBytes=256m` | 设置大页 256MB |

> **指针压缩：** 堆 < 32G 默认开，能省 30% 内存。超过 32G 自动关。

---

### 1.6 编译参数

| 参数 | 含义 |
|------|------|
| `-XX:CICompilerCount=12` | JIT 编译器线程数 |
| `-XX:+UseFastAccessorMethods` | 快速方法调用优化 |

---

### 1.7 调试参数

| 参数 | 含义 |
|------|------|
| `-agentlib:jdwp=...address=65012` | 远程调试端口 65012 |

---

## 二、JVM 参数查看三板斧

### 2.1 `jps -l` + `jinfo`

```bash
jps -l                    # 查 Java 进程号
jinfo -flag <参数名> <pid> # 查单个参数当前值
jinfo -flags <pid>        # 查所有参数当前值

# 示例
jinfo -flag MaxHeapSize 12345                    # 查最大堆
jinfo -flag +PrintGCDetails 12345  # 动态开启 GC 日志（需 manageable）
```

---

### 2.2 `java -XX:+PrintFlagsInitial`

```bash
java -XX:+PrintFlagsInitial -version  # 打印所有参数的默认值
java -XX:+PrintCommandLineFlags -version  # 当前生效的命令行参数
```

**输出格式解读：**

```
intx ActiveProcessorCount  = -1    {product}    # = 默认值
uintx MaxHeapSize         := 1073741824  {product}    # := 被修改过
```

| 符号 | 含义 |
|:---:|---|
| `=` | JVM 默认值，未被修改 |
| `:=` | 被命令行或 `jinfo` 动态改过 |

---

## 三、方法区 vs 元数据空间

### 一句话

> **JDK 7 及之前：** 永久代（PermGen）实现方法区，JVM 堆外固定大小。
> **JDK 8 及之后：** 元数据空间（Metaspace）取代永久代，用本地内存，动态扩容。

---

### 对比表

| | 永久代 (≤ JDK 7) | 元数据空间 (≥ JDK 8) |
|---|---|---|
| **内存来源** | JVM 堆外固定区域 | **操作系统本地内存** |
| **大小** | 固定，设小了 OOM | **动态扩展**，理论上只受系统内存限制 |
| **配置参数** | `-XX:PermSize` / `-XX:MaxPermSize` | `-XX:MetaspaceSize` / `-XX:MaxMetaspaceSize` |
| **OOM 风险** | 大量类加载 → `OutOfMemoryError: PermGen` | 少得多，可设 `-XX:MaxMetaspaceSize` 兜底 |
| **类卸载** | GC 不频繁回收 | GC 可回收类元数据 |

---

### 配置建议

```bash
# Metaspace 初始值和最大值
-XX:MetaspaceSize=128m        # 初始值，到达触发 Full GC
-XX:MaxMetaspaceSize=256m     # 上限，防本地内存被吃光
```

---

## 四、面试话术

### JVM 核心参数（30 秒版）

> **堆内存：** `-Xms` 初始，`-Xmx` 最大，`-Xmn` 年轻代，`-Xss` 栈。
> **GC 日志：** `-Xloggc <path>` + `-XX:+PrintGCDetails` + `-XX:+PrintGCDateStamps`。
> **OOM 排查：** `-XX:+HeapDumpOnOutOfMemoryError` + `-XX:HeapDumpPath=/path/xx.hprof`。
> **查看参数：** `jps -l` → `jinfo -flags pid`；`java -XX:+PrintFlagsInitial`，`:=` = 被改过。

### 永久代 vs 元数据空间（15 秒版）

> **JDK 7 永久代**——JVM 固定大小，设小了 OOM。**JDK 8 元数据空间**——操作系统本地内存，动态扩容，不再 OOM。配置从 `PermSize` 换成 `MetaspaceSize`。
