当然，大飞哥，`jmap` 是 HotSpot JVM 提供的经典工具，用于分析和导出 **堆内存使用情况**，在 **内存泄漏排查**、**对象分布分析**、**导出堆快照（heap dump）** 等方面非常有用。

---

## 🔧 基本格式：

```bash
jmap [option] <pid>
```

常配合 `jps` 一起使用：

```bash
jmap -histo:live $(jps | grep MyApp | awk '{print $1}')
```

---

## ✅ 常用命令及案例：

---

### 📌 案例 1：查看对象统计直方图（`-histo`）

```bash
jmap -histo <pid>
```

### 输出示例：

```
 num     #instances         #bytes  class name
----------------------------------------------
   1:         105432       7343920  [C
   2:          54512       6540248  java.lang.String
   3:          12030       1432104  java.util.HashMap
   ...
```

🧠 用途：

* 快速查看哪些类占用了最多的内存
* 排查是否有 **对象泄漏（数量异常）**

---

### 📌 案例 2：仅统计活跃对象（`-histo:live`）

```bash
jmap -histo:live <pid>
```

与 `-histo` 的区别：只统计 **仍被引用** 的对象，更适合排查 **内存泄漏**

---

### 📌 案例 3：导出堆快照（Heap Dump）

```bash
jmap -dump:format=b,file=/tmp/heapdump.hprof <pid>
```

🧠 用途：

* 在 GC 前后导出堆，结合 `MAT`、`VisualVM`、`JProfiler` 分析对象引用链，定位 OOM 或内存泄漏根因。

⚠️ 注意：

* 导出大堆（>1G）期间会 Stop-The-World，建议在线上慎用或配合 `-XX:+HeapDumpOnOutOfMemoryError` 使用

---

### 📌 案例 4：查看堆配置摘要（`-heap`）

```bash
jmap -heap <pid>
```

### 输出示例：

```
Heap Configuration:
   MinHeapFreeRatio         = 0
   MaxHeapFreeRatio         = 100
   MaxHeapSize              = 4294967296 (4096.0MB)
   NewSize                  = 1363144 (1.25MB)
   ...
```

🧠 用途：

* 快速查看 JVM 实际使用的堆内存设置（`-Xms`、`-Xmx` 等）

---

### 📌 案例 5：列出堆中所有 class loader 加载的类（`-clstats`）

```bash
jmap -clstats <pid>
```

### 输出示例：

```
ClassLoader               Classes   Bytes
sun.misc.Launcher$App...     5120    8.3MB
...
```

🧠 用途：

* 分析类加载器是否泄漏（典型场景如：Tomcat 热部署导致类加载器泄漏）

---

### 📌 案例 6：列出 JVM 加载的 class 信息（`-finalizerinfo`）

```bash
jmap -finalizerinfo <pid>
```

🧠 用途：

* 查看等待 Finalizer 的对象是否堆积，常用于诊断 **System.gc() 被滥用**、**资源未关闭**

---

## ⚠️ 注意事项：

| 限制       | 描述                                       |
| -------- | ---------------------------------------- |
| 权限限制     | 需要和目标 JVM 使用 **相同用户**（或 root）执行          |
| 大堆 dump  | `jmap -dump` 对大堆会 STW，慎用于生产              |
| JDK 版本限制 | JDK 9+ 建议优先使用 `jcmd` 代替部分 `jmap` 功能（更稳定） |

---

## ✅ 附：结合分析工具使用

你可以用以下工具加载 `jmap` 导出的 `.hprof` 文件：

* Eclipse MAT（Memory Analyzer Tool）
* VisualVM（插件：Visual GC + Heap Dump Viewer）
* IntelliJ IDEA 自带的 Profiler（Ultimate）

---

是否需要我给你写一个脚本，自动生成 `histo` + `heap` + `dump` 文件，供你离线排查？还能加上时间戳和 GC 触发记录。
