`jstatd` 是 JVM 提供的守护进程，用于把本地 JVM 的监控接口（JMX／PerfData）暴露给远程客户端（如 `jstat`、`jconsole`）使用。典型场景是在生产或测试机上启动 `jstatd`，然后在运维机器上远程查看或收集 JVM 统计数据。

---

## 一、准备安全策略文件

为了允许远程访问，你需先定义一个 Java 安全策略（例如 `jstatd.all.policy`），内容示例：

```text
grant codebase "file:${java.home}/../lib/tools.jar" {
  permission java.security.AllPermission;
};
```

保存到服务器上的某个目录，如 `/opt/jstatd/jstatd.all.policy`。

---

## 二、启动 `jstatd`

### 1. 使用默认端口（1099）启动

```bash
nohup jstatd -J-Djava.security.policy=/opt/jstatd/jstatd.all.policy > jstatd.log 2>&1 &
```

* `-J-Djava.security.policy`：指定安全策略文件
* 默认 RMI Registry 监听端口：1099

### 2. 指定 RMI Registry 端口和服务端口

```bash
nohup jstatd \
  -J-Djava.security.policy=/opt/jstatd/jstatd.all.policy \
  -p 12345 \
  -J-Djava.rmi.server.hostname=192.168.1.100 \
  > jstatd.log 2>&1 &
```

* `-p 12345`：指定 Registry 端口为 12345
* `-Djava.rmi.server.hostname`：指定服务端对外的主机名或 IP，避免 DNS 解析问题

---

## 三、远程使用 `jstat` 连接

当 `jstatd` 在目标机器（192.168.1.100）上启动后，可在运维机上执行：

```bash
# 列出所有 Java 进程
jps -m 192.168.1.100:12345
```

```bash
# 查看远程某进程的 GC 状况
jstat -gc 192.168.1.100:12345 <pid> 1000 5
```

* `<pid>`：通过上一步 `jps` 得到的远程进程 ID
* `1000 5`：每 1 秒打印一次，总共打印 5 次

---

## 四、在 Docker 容器中使用

1. 在容器内准备策略文件并启动 `jstatd`，同时映射 1099 端口：

   ```bash
   docker run -d --name jvm-app \
     -p 8080:8080 \
     -p 1099:1099 \
     my-java-app \
     sh -c "jstatd -J-Djava.security.policy=/app/jstatd.all.policy"
   ```

2. 在宿主机或其他机器上远程执行：

   ```bash
   jstat -gc host.docker.internal:1099 1
   ```

---

## 五、配合监控脚本自动化

可以写一个简单 Bash 函数，一键收集远程 GC、类加载、堆等数据：

```bash
function remote_jvm_stats() {
  local host=$1; local port=${2:-1099}; local pid=$3
  echo "### GC ###"
  jstat -gc    ${host}:${port} ${pid}
  echo "### Class ###"
  jstat -class ${host}:${port} ${pid}
  echo "### Compiler ###"
  jstat -compiler ${host}:${port} ${pid}
}
# 调用示例：remote_jvm_stats 192.168.1.100 12345 9876
```

---

## 六、注意事项

* **安全**：生产环境应慎用 `AllPermission` 策略，可根据最小化原则精细授权。
* **网络**：确保 RMI 端口（1099 或自定义）在防火墙中放通。
* **版本**：`jstatd` 需与目标 JVM 版本一致（或兼容）。

---

通过以上案例，你就能在远程环境中安全、高效地使用 `jstatd` + `jstat` 对 JVM 进行实时监控与诊断。
