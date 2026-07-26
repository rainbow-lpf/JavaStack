当然，`jinfo` 是 Java 自带的一款命令行工具，用于 **在运行时查看或修改 JVM 参数配置（包括系统属性和 JVM 启动参数）**，常用于排查线上运行参数是否设置正确、是否开启 GC 日志、是否启用了某些优化开关等。

---

## 📌 基本命令格式：

```bash
jinfo [option] <pid>
```

---

## ✅ 案例 1：查看 JVM 启动参数（Flags）

```bash
jinfo -flags <pid>
```

### 示例输出：

```
-XX:CICompilerCount=4
-XX:InitialHeapSize=268435456
-XX:+PrintGC
-XX:+UseParallelGC
```

### 📌 用途：

* 快速判断 GC 策略（如 UseParallelGC、UseG1GC）
* 是否开启诊断参数（如 PrintGC、PrintGCDetails）
* 堆大小设置是否符合预期（InitialHeapSize、MaxHeapSize）

---

## ✅ 案例 2：查看系统属性（System Properties）

```bash
jinfo -sysprops <pid>
```

### 示例输出：

```
java.runtime.name = Java(TM) SE Runtime Environment
java.vm.version = 25.281-b09
file.encoding = UTF-8
user.timezone = Asia/Shanghai
spring.profiles.active = prod
```

### 📌 用途：

* 检查应用配置是否正确传入，例如：

    * `spring.profiles.active`
    * `file.encoding`
    * `user.timezone`

---

## ✅ 案例 3：查看所有信息（Flags + Sysprops）

```bash
jinfo <pid>
```

### 📌 用途：

一口气查看所有启动参数和系统属性，便于全面排查配置问题。

---

## ✅ 案例 4：动态修改 JVM 参数（需开启 `-XX:+UnlockDiagnosticVMOptions`）

```bash
jinfo -flag +PrintGC <pid>
```

* 将某个布尔型 JVM 参数从关闭变为开启（例如动态开启 GC 日志）
* `+` 是开启，`-` 是关闭
* ⚠️ 只支持部分参数动态修改，修改失败会有提示

示例（关闭 GC 日志）：

```bash
jinfo -flag -PrintGC <pid>
```

---

## ✅ 案例 5：在 Docker 中使用（额外说明）

如果你的 Java 应用运行在 Docker 容器中，可能需要如下方式使用：

```bash
docker exec -it <container_id> jinfo -flags <pid>
```

或者在容器中用 `ps -ef | grep java` 查出 PID，再用 jinfo 查看。

---

## 🚨 注意事项：

| 限制项               | 说明                          |
| ----------------- | --------------------------- |
| 权限                | 必须与目标 JVM 运行用户相同，或使用 `sudo` |
| 某些参数不可动态更改        | 比如堆大小相关参数                   |
| Java 9+ 后期逐步不推荐使用 | 建议使用 `jcmd` 替代              |

---

## 🔧 实战：排查 GC 策略是否正确配置

```bash
jinfo -flags <pid> | grep GC
```

常见输出：

```
 -XX:+UseG1GC
 -XX:+PrintGCDetails
```

说明已启用 G1 GC，并开启了 GC 日志。

---

如果你想，我可以写一个自动收集 `jinfo`、`jstat`、`jstack`、`jmap` 这些工具的诊断脚本，一键输出 JVM 全面状态。是否需要？
