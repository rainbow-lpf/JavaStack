当然，以下是几个常见的 `jps`（Java Virtual Machine Process Status Tool）命令使用案例，主要用于排查 Java 应用运行状态、诊断问题时定位 JVM 进程：

---

## 📌 什么是 `jps`

`jps` 是 JDK 自带的命令行工具，用于列出当前主机上所有正在运行的 Java 进程，适合用于故障排查、监控、配合其他工具如 `jstack`、`jmap` 等一起使用。

---

## ✅ 案例 1：列出所有 Java 进程

```bash
jps
```

### 🔍 示例输出：

```
12345 MySpringBootApp
23456 Jps
34567 Kafka
```

说明：

* 第一列是 **进程 ID（PID）**
* 第二列是 **主类名**（例如启动类或 jar 包的主类）

---

## ✅ 案例 2：显示进程的完整类名和传参

```bash
jps -l
```

### 🔍 示例输出：

```
12345 com.example.MainApplication
23456 sun.tools.jps.Jps
34567 org.apache.kafka.Kafka
```

用途：

* 可查看完整类名或 JAR 路径，有助于区分多个类似进程

---

## ✅ 案例 3：显示传入的 JVM 参数

```bash
jps -v
```

### 🔍 示例输出：

```
12345 MySpringBootApp -Xms512m -Xmx1024m -Dspring.profiles.active=prod
23456 Jps -Dapplication.home=/opt/java/jdk
```

用途：

* 用于排查 JVM 启动参数（如堆内存、系统属性等）

---

## ✅ 案例 4：显示启动时的主类、JAR 名及传入参数

```bash
jps -lmv
```

* `-m`：显示传给 main() 的参数
* `-v`：显示 JVM 启动参数
* `-l`：显示主类或 jar 完整路径

组合后信息最全，适用于诊断多实例、参数冲突问题。

---

## ✅ 案例 5：结合其他工具定位线程阻塞

```bash
jstack `jps | grep MyApp | awk '{print $1}'` > thread_dump.txt
```

用途：

* 通过 `jps` 定位 Java 应用 PID，再用 `jstack` 打印线程栈，排查死锁或线程卡死问题。

---

## ❗ 注意事项：

* 某些情况下，`jps` 可能无法识别无 `tools.jar` 的进程（如 jre 模式运行）。
* 在生产环境上使用前应考虑权限（可能需要 `root` 或进程所属用户）。
* `jps` 依赖本地 JVM 的 `attach` 机制，跨用户/容器访问可能受限。

---

如果你需要更多进阶用法，比如结合 `jmap`、`jcmd` 进行堆内存分析，也可以继续告诉我。
