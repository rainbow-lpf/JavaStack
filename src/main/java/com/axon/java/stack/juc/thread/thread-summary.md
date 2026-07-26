# JUC 线程基础 — 面试总结

> 目录：`com.axon.java.stack.juc.thread`

---

## 一、volatile 可见性

### 案例：`ThreadVolatileTestDemo`

```java
private static volatile boolean flag = true;

// 线程1：轮询 flag
new Thread(() -> {
    while (flag) { ... }
}).start();

// 线程2：3秒后改 flag → 线程1立刻看到变化，退出循环
flag = false;
```

### 原理

| 无 volatile | 有 volatile |
|---|---|
| 线程1 可能在本地缓存死循环 | 线程2 修改后强制刷回主存，线程1 每次都从主存取 |
| 不可见 | **可见性保证** |

### 面试回答

> volatile 保证可见性：一个线程修改，其他线程立刻看到。底层通过 **内存屏障** 实现——写 barrier 强制刷主存，读 barrier 强制读主存。

---

## 二、AtomicBoolean — volatile + CAS 的套装

### 案例：`ThreadAtomTestDemo`

```java
private static volatile AtomicBoolean flag = new AtomicBoolean(true);

while (flag.get()) { ... }   // 读
flag.set(false);             // 写 — 底层 CAS
```

### 和纯 volatile 的区别

| volatile | AtomicBoolean |
|---|---|
| `flag = false` — 只负责可见性 | `flag.set(false)` — 底层 CAS，加了一层原子性 |
| 复合操作（如 `if (flag) flag=false`）不安全 | `compareAndSet` 保证读-改-写原子 |

### 面试回答

> AtomicBoolean 用 volatile + CAS 打包了一个原子的布尔值。`get()` 走 volatile 读，`set()` 走 volatile 写，`compareAndSet` 走 CAS。比裸 volatile 多了原子复合操作能力。

---

## 三、线程中断 — interrupt 协商机制

### 3.1 基础用法

**案例：`ThreadInterruptTestDemo`**

```java
Thread t1 = new Thread(() -> {
    while (true) {
        if (Thread.currentThread().isInterrupted()) {  // 检查中断标志
            break;                                      // 协商退出
        }
    }
});
t1.start();
t1.interrupt();  // 设置中断标志 = true
```

> 调用 `interrupt()` 不会立刻杀死线程，只是把中断标志设为 true。线程自己在代码里检查，自行决定要不要退出。——这是一个协商机制。

---

### 3.2 isInterrupted() — 不清理标志（实例方法）

**案例：`ThreadInterruptTes02tDemo`**

```java
t1.interrupt();
t1.isInterrupted();  // true
t1.isInterrupted();  // true  ← 标志还在，不清除
```

**注意：线程运行结束后 JVM 清除中断状态，此时 `isInterrupted()` 返回 false。**

---

### 3.3 interrupted() — 返回并清除标志（静态方法）

**案例：`ThreadInterruptedDemo`**

```java
Thread.interrupted();        // false（没被中断过）

Thread.currentThread().interrupt();
Thread.interrupted();        // true  ← 同时清除标志
Thread.interrupted();        // false ← 上次调用已清除
```

---

### 3.4 sleep/wait/join 遇到中断 — 清标志 + 抛异常

**案例：`ThreadInterruptTest04Demo`**

```java
while (true) {
    if (Thread.currentThread().isInterrupted()) { break; }
    try {
        Thread.sleep(30);           // 这里如果被中断
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // ⚠️ 必须重新设置！
        e.printStackTrace();
    }
}
```

**关键：sleep 收到中断后，标志位被清掉了。不重设 `interrupt()` 的话上面 `isInterrupted()` 永远 false → 死循环。**

---

## 四、对比速记表

### isInterrupted vs interrupted

| | `isInterrupted()` | `interrupted()` |
|---|---|---|
| 类型 | 实例方法 | 静态方法 |
| 调用方式 | `t1.isInterrupted()` | `Thread.interrupted()` |
| 作用对象 | 指定线程 | 当前线程 |
| 清除中断标志？ | ❌ 不清除 | ✅ 清除 |

### volatile vs AtomicBoolean

| | volatile | AtomicBoolean |
|---|---|---|
| 可见性 | ✅ | ✅ |
| 原子复合操作 | ❌ | ✅ (`compareAndSet`) |
| 典型场景 | 状态标志简单赋值 | 需要 `compareAndSet` 的场景 |

---

## 五、面试话术（一分钟版）

> **volatile** 解决可见性问题，一个线程改，其他线程立刻看到，底层内存屏障。
>
> **AtomicBoolean** 在 volatile 上包了一层 CAS，多了原子复合操作能力。
>
> **线程中断** 是个协商机制：`interrupt()` 置标志，线程主动检查 `isInterrupted()` 决定是否退出。`interrupted()` 是静态方法，检查当前线程并清除标志。
>
> **sleep/wait/join 遇到中断** 抛 `InterruptedException` 且自动清除标志，catch 里必须重新 `interrupt()`，否则死循环。
