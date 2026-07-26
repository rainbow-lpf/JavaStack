当然，大飞哥，`jstack` 是 Java 调试中**最常用也最关键的工具之一**，主要用于查看 Java 应用的**线程栈信息**，在**排查线程死锁、卡顿、CPU飙高、阻塞、死循环**等问题时非常有效。

---

## 🧰 基本语法：

```bash
jstack <pid>
```

也可以输出到文件：

```bash
jstack <pid> > thread_dump.txt
```

---

## ✅ 典型使用案例：

---

### 📌 案例 1：排查线程死锁

```bash
jstack <pid> | grep -A 20 "Found one Java-level deadlock"
```

或者直接查找锁标志：

```bash
jstack <pid> | grep -i "deadlock"
```

### 输出示例（节选）：

```
Found one Java-level deadlock:
"Thread-1":
  waiting to lock monitor 0x000000000xxx (object A),
  which is held by "Thread-2"
"Thread-2":
  waiting to lock monitor 0x000000000yyy (object B),
  which is held by "Thread-1"
```

🧠 说明：两个线程相互等待对方释放锁，产生死锁。

---

### 📌 案例 2：排查应用卡顿、无响应（找出阻塞线程）

```bash
jstack <pid> | grep -A 10 BLOCKED
```

或者：

```bash
jstack <pid> | grep -A 10 "waiting to lock"
```

你将看到类似：

```
"http-nio-8080-exec-5" #25 prio=5 os_prio=0 tid=0x00007fabc1234000 nid=0x2b03 BLOCKED on object monitor
```

🧠 表示该线程在竞争锁资源，排查是否代码逻辑引起大量线程争锁。

---

### 📌 案例 3：排查 CPU 飙高（定位热点线程）

1️⃣ 先找到占用高 CPU 的线程（Linux 示例）：

```bash
top -Hp <pid>
```

输出：

```
  PID USER   PR  NI VIRT  RES  SHR S %CPU %MEM    TIME+  COMMAND
12345 root   20   0  ...  ...  ... R 250.0  ...    1:23.45  java
```

2️⃣ 将该线程 PID 转为十六进制（示例：十进制 `12345` → 十六进制 `0x3039`）：

```bash
printf "0x%x\n" 12345
```

3️⃣ 用 jstack 查看该线程的调用栈：

```bash
jstack <pid> | grep -A 30 "nid=0x3039"
```

🧠 快速找出哪个方法/循环导致 CPU 飙高。

---

### 📌 案例 4：查看线程池状态和堆积线程数

搜索执行线程：

```bash
jstack <pid> | grep -A 20 'http-nio'
```

或者：

```bash
jstack <pid> | grep "RUNNABLE" | wc -l
```

🧠 查看当前有多少线程处于活跃状态，是线程池处理不过来还是请求异常阻塞。

---

### 📌 案例 5：批量采样分析（线程变化趋势）

定时采集线程栈 5 次，每 5 秒：

```bash
for i in {1..5}; do
  echo "====== $i ======" >> dump.txt
  jstack <pid> >> dump.txt
  sleep 5
done
```

🧠 用于分析问题是否持续，线程是否在增长，某些栈是否一直卡住。

---

## ✅ 输出重点字段说明：

| 字段                                 | 含义        |
| ---------------------------------- | --------- |
| `"thread-name"`                    | 线程名       |
| `RUNNABLE` / `WAITING` / `BLOCKED` | 当前线程状态    |
| `locked` / `waiting to lock`       | 是否在等待/持有锁 |
| `at com.xxx.YourClass.method`      | 方法调用栈     |

---

## ✅ 推荐配合工具：

| 工具                         | 用途                |
| -------------------------- | ----------------- |
| `jps`                      | 获取 Java 应用 PID    |
| `top -Hp`                  | 查看 Java 线程 CPU 使用 |
| `jstack`                   | 获取线程栈             |
| `grep` / `awk`             | 过滤特定线程信息          |
| `VisualVM` / IDEA Profiler | 图形化分析线程状态         |

---

## ⚠️ 注意事项：

| 项目   | 描述                           |
| ---- | ---------------------------- |
| 权限要求 | 需与 Java 进程相同用户运行             |
| 大量线程 | 如果线程很多（几千个），输出会非常大，建议分页分析    |
| 输出格式 | 建议重定向到文件分析，便于文本工具/IDE 搜索引用关系 |

---

如你需要一个一键生成 thread dump + 自动分析 RUNNABLE、BLOCKED、死锁、热点线程的 Shell 脚本，我可以帮你写一个简洁实用版，要不要？
