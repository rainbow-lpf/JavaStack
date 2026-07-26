`jstat`（JVM Statistics Monitoring Tool）是 JDK 提供的一个强大工具，用于 **实时查看 JVM 的各种运行时统计信息**，特别常用于排查内存、GC、类加载、JIT 等问题。

---

## 🔧 常用格式：

```bash
jstat [option] <vmid> [interval] [count]
```

* **option**：指定你要查看的统计项（如 GC 状况、类加载情况等）
* **vmid**：进程 ID（可通过 `jps` 获取）
* **interval**：时间间隔（单位为毫秒）
* **count**：输出次数

---

## ✅ 1. 查看类加载情况（`-class`）

```bash
jstat -class <pid> 1000 5
```

### 输出示例：

```
Loaded  Bytes  Unloaded  Bytes     Time
  3625   8653.3        0     0.0     0.45
```

说明：

* 当前已加载类数量、总字节数
* 已卸载类数量及字节数
* 加载/卸载类所花时间（秒）

---

## ✅ 2. 查看 GC 情况（`-gc`）

```bash
jstat -gc <pid> 1000 10
```

### 输出示例（关键指标）：

```
 S0C    S1C    S0U    S1U      EC       EU       OC         OU         YGC     YGCT    FGC    FGCT     GCT
1024.0 1024.0   0.0    0.0   8192.0   4096.0   10240.0     4096.0       10     0.123     2     0.456    0.579
```

含义解释（重点）：

* **S0C/S1C**：Survivor 0/1 区容量
* **S0U/S1U**：Survivor 0/1 区使用量
* **EC/EU**：Eden 区容量 / 使用
* **OC/OU**：Old 区容量 / 使用
* **YGC/YGCT**：Young GC 次数 / 时间
* **FGC/FGCT**：Full GC 次数 / 时间
* **GCT**：总 GC 时间

---

## ✅ 3. 查看 GC 元数据空间情况（Java 8+，`-gcmetacapacity`）

```bash
jstat -gcmetacapacity <pid> 1000 3
```

### 输出字段示例：

* **MCMN**：最小元空间容量
* **MCMX**：最大元空间容量
* **MC**：当前元空间容量
* **MU**：当前已使用
* **CCMN/MX/CC/U**：Compressed Class Space（压缩类空间）

---

## ✅ 4. 查看 JVM 编译情况（JIT 编译器，`-compiler`）

```bash
jstat -compiler <pid>
```

输出：

```
Compiled  Failed  Invalid  Time  FailedType  FailedMethod
  2430       0       0     15.23     0          -
```

说明：

* 编译过的方法数量
* 编译失败次数及总时间（单位秒）

---

## ✅ 5. 查看垃圾收集统计摘要（`-gcutil`）

```bash
jstat -gcutil <pid> 1000 5
```

这个是 GC 诊断中最常用的形式！

输出示例：

```
 S0     S1     E      O      M     CCS   YGC   YGCT   FGC   FGCT     GCT
 0.00   0.00  50.00  40.00  80.00  75.00   10   0.12     2   0.35     0.47
```

含义同 `-gc`，但使用百分比更清晰，适合观察 GC 行为趋势。

---

## 🛠 实战场景举例

### 场景：排查频繁 GC

```bash
jstat -gcutil <pid> 1000 10
```

观察 YGC/FGC 是否快速增长，以及 Eden/S0/S1/Old 区是否频繁回收或占满。

### 场景：观察类加载问题

```bash
jstat -class <pid> 1000 10
```

快速判断类是否频繁加载，或是否存在卸载异常。

---

## ❗注意事项

1. `jstat` 必须和目标进程使用 **相同 JDK**（版本不同可能 attach 不上）
2. 某些 Linux 系统需确保目标进程与当前用户相同（或用 root 运行）
3. 不适用于容器内运行的 JVM（如 Docker）时可能需要额外配置 `-XX:+PerfDisableSharedMem`

---

需要我帮你写一个结合 `jps + jstat + jstack` 的综合诊断脚本吗？可一键快速收集 JVM 的状态信息。
