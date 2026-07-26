# volatile — 面试总结

> 目录：`com.axon.java.stack.juc.Volatile`

---

## 一、volatile 是什么

**三大特性：可见性、有序性、无原子性。**

| 特性 | 保证？ | 说明 |
|------|:---:|------|
| **可见性** | ✅ | 一个线程改，其他立刻看到 |
| **有序性** | ✅ | 禁止指令重排序（内存屏障） |
| **原子性** | ❌ | `i++` 不是原子的 |

---

## 二、可见性：有 vs 没有 volatile

### ❌ 没有 volatile = 不可见

```java
// VolatileTestDemo.java
private static boolean flag = true;   // 没有 volatile

// 线程AAA
while (flag) {}   // 死循环，永远看不到 main 线程的修改

// main 线程
flag = false;     // 改了，但 AAA 不知道
```

**原因：** 每个线程把 flag 从主内存拷到自己的工作内存（CPU 缓存）。main 改完只在自己缓存里生效，AAA 一直读自己的缓存。

### ✅ 有 volatile = 即时通知

```java
// VolatileTestDemo02.java
private static volatile boolean flag = true;  // 加 volatile

// 线程AAA：2秒后退出循环
while (flag) {}

// main 线程：修改后强制刷回主内存，并通知其他线程缓存失效
flag = false;   // AAA 立刻看到
```

---

## 三、Java 内存模型 JMM

### 案例：`JMMTest.java`

```java
class MyNumber {
    volatile int number = 10;   // volatile 保证可见性
}

// 线程AAA 3秒后：number = 1205
// main 线程：while (number == 10) → 立刻感知到，退出循环
```

```
        线程AAA                     main 线程
          │                           │
    ┌─────┴─────┐              ┌──────┴──────┐
    │ 工作内存   │              │  工作内存    │
    │ number=1205│             │  number=1205 │
    └─────┬─────┘              └──────┬──────┘
          │    volatile 写      volatile 读   │
          ▼         强制刷回 ◄────────── 缓存失效
    ┌─────────────────────────────────────┐
    │           主 物 理 内 存             │
    │         number = 1205               │
    └─────────────────────────────────────┘
```

> **JMM 规定：** 所有变量存主内存，线程拷贝到工作内存。volatile 保证写后立刻刷回主内存并**总线窥探**通知其他线程缓存失效。

---

## 四、禁止指令重排序 — 四种内存屏障

### 案例：`VolatileTestDemo03.java`

```java
class MyVolatileTest03 {
    int i = 0;
    volatile boolean flag = false;

    public void writer() {
        i = 2;           // ① 普通写
        // → StoreStore 屏障 ←
        flag = true;     // ② volatile 写
        // → StoreLoad 屏障 ←
    }

    public void read() {
        if (flag) {      // ③ volatile 读
            // → LoadLoad 屏障 ←
            // → LoadStore 屏障 ←
            System.out.println(i);  // ④ 普通读
        }
    }
}
```

**没有内存屏障 → CPU/编译器可能重排，导致 `i=2` 跑到 `flag=true` 后面，reader 看到 flag 是 true 但 i 还是 0。**

---

### 四种屏障对应图

```
writer():
   i = 2;
   ──────── StoreStore 屏障 ────────  ← 禁止 ① 跑到 ② 后面
   flag = true;
   ──────── StoreLoad 屏障 ───────── ← 禁止 ② 与后面的读重排序

read():
   if (flag) {
       ─── LoadLoad 屏障 ─── ← 禁止 ③ 与后面的读重排序
       ─── LoadStore 屏障 ── ← 禁止 ③ 与后面的写重排序
       System.out.println(i);
   }
```

---

### 四条规则（背这个）

| 规则 | 含义 |
|------|------|
| volatile 写**之前**的普通写 | 不能重排到 volatile 写**之后** |
| volatile 读**之后**的普通读写 | 不能重排到 volatile 读**之前** |
| volatile 写 → volatile 读 | 不能重排序 |

---

### 字节码层面

被 volatile 修饰的变量，编译后自动打上 **`ACC_VOLATILE`** 标识。JVM 遇到这个标识，自动在对应位置插入内存屏障。

```java
// javap -v 查看
volatile boolean flag;
  flags: ACC_VOLATILE    ← JVM 看到这个就知道要插屏障
```

---

## 五、DCL 双重检查锁 — volatile 防指令重排

### 案例：`SingleVolatileTest.java`

```java
public class SingleVolatileTest {
    private static volatile SingleVolatileTest singleTest = null;  // ← volatile 必须有！

    public static SingleVolatileTest getSingleTest() {
        if (singleTest == null) {                    // 第一层检查（无锁）
            synchronized (SingleVolatileTest.class) {
                if (singleTest == null) {            // 第二层检查（有锁）
                    singleTest = new SingleVolatileTest();  // 只创建一次
                }
            }
        }
        return singleTest;
    }
}
```

---

### 为什么必须 volatile？

`new SingleVolatileTest()` 不是原子操作，底层拆成三步：

```
1. 分配内存空间
2. 执行构造函数，初始化对象
3. 将引用指向内存空间
```

**CPU 可能重排成 1 → 3 → 2**。如果 3 执行完但 2 还没执行，另一个线程看到 `singleTest != null`，返回的是一个**半成品对象**。

```
线程A： 1.分配 → 3.指向空间(还没初始化！)
                              ↓
线程B： if(singleTest != null)  → 拿到一个未初始化的对象 → 崩！
```

**volatile 阻止了 2 和 3 的重排序，保证对象完全初始化后才把引用发布出去。**

---

### 比喻

> 公司给新同事分配工位——，人还没到。别人看到工位牌以为是新同事坐在这儿，实际人不在，不能用。

---

## 六、volatile 不保证原子性

### 案例：`VolatileTestDemo04.java`

```java
private volatile int value;       // volatile 读 ok
public int getValue() { return value; }

public synchronized int setValue() {   // 必须加锁，volatile 不行
    return value++;   // 读-改-写，三步！volatile 管不了原子性
}
```

> `value++` = 读 → 加 1 → 写。volatile 只管单步读写的可见性，管不住多步复合操作的原子性。**复合操作用 `synchronized` 或 `AtomicInteger`。**

---

## 七、volatile 三大使用场景

| 场景 | 示例 | 案例 |
|------|------|------|
| **状态标志** | 控制线程退出 | `VolatileTestDemo02` |
| **低开销读写** | volatile 读 + synchronized 写 | `VolatileTestDemo04` |
| **DCL 单例** | 禁止 `new` 指令重排 | `SingleVolatileTest` |

---

## 八、面试话术（30 秒版）

> **volatile 两个作用：可见性 + 禁止指令重排。**
>
> **可见性：** 写后立刻刷回主内存，总线窥探通知其他线程缓存失效。解决 JMM 线程间不可见问题。
>
> **有序性：** 四种内存屏障——StoreStore 在前、StoreLoad 在后、LoadLoad/LoadStore 在读后。保证 volatile 写前不后移，volatile 读后不前移。
>
> **不保证原子性：** `i++` 三步，volatile 管不了，用 `AtomicInteger` 或 `synchronized`。
>
> **DCL 必须 volatile：** 防 `new` 对象时的指令重排（分配内存 → 初始化 → 赋引用），避免别的线程拿到半成品对象。
