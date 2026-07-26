# 八锁案例 — 面试总结

> 目录：`com.axon.java.stack.juc.八锁案例`

---

## 一句话先行

> **8 个案例只考察三件事：①锁的是对象还是类？②普通方法与同步方法抢不抢？③两个对象是不是同一把锁？**

---

## 场景分析

```java
class Phone {
    // ① 同步方法（实例锁 = this）
    public synchronized void sendEmail() { sleep(3); print("email"); }
    public synchronized void sendSms()   { print("sms"); }

    // ② 普通方法（无锁）
    public void hello() { print("hello"); }

    // ③ 静态同步方法（类锁 = Phone.class）
    public static synchronized void sendEmail() { sleep(3); print("email"); }
    public static synchronized void sendSms()   { print("sms"); }
}
```

**规则速记：**

| 修饰符 | 锁对象 | 竞争范围 |
|--------|--------|---------|
| `synchronized` 实例方法 | **this**（当前实例） | 同一个对象的其他实例 sync 方法 |
| `static synchronized` | **Phone.class** | Phone 类的所有静态 sync 方法 |
| 普通方法 | **无锁** | 和谁也不竞争 |
| 不同对象 | 各自持有自己的 this | **不竞争** |

---

## 八个案例详解

### 锁 1 + 2：同一对象，多个 sync 方法 — 同一把锁

```java
Phone phone = new Phone();   // 一部手机

// 线程A：phone.sendEmail()  ← 锁 this
// 线程B：phone.sendSms()    ← 也锁 this，A 不释放，B 等

// 1. sendEmail 无 sleep → email 先？sms 先？不确定（CPU 调度）
// 2. sendEmail 有 sleep 3秒 → 一定是 email 先，sms 等 3 秒
```

**结论：** 同一对象内，所有 synchronized 实例方法共享 `this` 一把锁。一个拿了，其他全都等。

---

### 锁 3：普通方法 + sync 方法 — 不竞争

```java
Phone phone = new Phone();

// 线程A：phone.sendEmail()  ← 锁 this
// 线程B：phone.hello()      ← 无锁！

// 结果：hello 直接执行，不等 email
// 打印顺序：hello → email
```

**结论：** 普通方法不抢锁，直接执行。

---

### 锁 4：两个对象，两个实例锁 — 不竞争

```java
Phone phone1 = new Phone();
Phone phone2 = new Phone();

// 线程A：phone1.sendEmail()  ← 锁 phone1 的 this
// 线程B：phone2.sendSms()    ← 锁 phone2 的 this

// 两把不同的锁！互不影响，并行执行
// 结果：sms 先（不等 3 秒），email 后
```

**结论：** 两个实例，两把 this，谁也不等谁。

---

### 锁 5：静态 sync 方法 ×2，一个对象 — 类锁

```java
// sendEmail 和 sendSms 都是 static synchronized
Phone phone = new Phone();

// 线程A：phone.sendEmail()  ← 锁 Phone.class（类锁）
// 线程B：phone.sendSms()    ← 也锁 Phone.class

// 同一把类锁！email 先，sms 等 3 秒
```

**结论：** `static synchronized` 锁 `类.class`，一个类只有一把类锁。

---

### 锁 6：静态 sync 方法 ×2，**两部**手机 — 还是一把类锁

```java
Phone phone1 = new Phone();
Phone phone2 = new Phone();

// 线程A：phone1.sendEmail()  ← 锁 Phone.class
// 线程B：phone2.sendSms()    ← 也锁 Phone.class

// 还是同一把锁！ phone1 和 phone2 没用，锁的是 Class
// 结果：email 先，sms 等 3 秒
```

**结论：** 类锁只有一把，创建 100 个对象还是同一个 `Phone.class`，照样竞争。

---

### 锁 7：一个静态 sync + 一个实例 sync，一部手机

```java
Phone phone = new Phone();

// sendEmail: static synchronized  → 锁 Phone.class   （类锁）
// sendSms:   synchronized         → 锁 this          （实例锁）

// 线程A：phone.sendEmail()  ← 类锁
// 线程B：phone.sendSms()    ← 实例锁（this）

// 两把不同的锁！不竞争 → sms 先，email 后
```

**结论：** 类锁和 this 锁是两把锁，不相关。

---

### 锁 8：一个静态 sync + 一个实例 sync，**两部**手机

```java
Phone phone1 = new Phone();
Phone phone2 = new Phone();

// 线程A：phone1.sendEmail()  ← 锁 Phone.class   （类锁）
// 线程B：phone2.sendSms()    ← 锁 phone2 的 this （实例锁）

// 还是两把不同的锁 → 不竞争 → sms 先，email 后
```

**结论：** 和锁 7 一样，类锁与实例锁互不干扰。2 个对象更不干扰。

---

## 八锁速记表

| 锁 | 条件 | 锁的是什么 | 竞争？ | 结果 |
|:---:|------|------|:---:|---|
| 1 | 一部手机，两个 sync | **this** | ✅ | 看调度 |
| 2 | 同上，email 有 sleep | **this** | ✅ | email 先 |
| 3 | 一个 sleep sync + 普通 hello | this vs 无锁 | ❌ | hello 先 |
| 4 | 两部手机，各 sync | **phone1.this ≠ phone2.this** | ❌ | sms 先 |
| 5 | 两个 static sync，一部手机 | **Phone.class** | ✅ | email 先 |
| 6 | 两个 static sync，两部手机 | **Phone.class**（唯一） | ✅ | email 先 |
| 7 | 一个 static sync + 一个实例 sync，一部手机 | **Phone.class ≠ this** | ❌ | sms 先 |
| 8 | 同上，两部手机 | **Phone.class ≠ this** | ❌ | sms 先 |

---

## 面试话术（30 秒版）

> **synchronized 实例方法锁 this，static synchronized 锁类.class，普通方法无锁。**
>
> **竞争只看是不是同一把锁：** 一个对象的多个实例 sync → 同一把 this 竞争。多个对象实例 sync → 各锁各的 this 不竞争。静态 sync → 全类只有一把 Phone.class 锁，不管几部手机都抢。
>
> **实例锁和类锁是两把锁，互不干扰。**

---

## 核心口诀

```
实例方法锁 this     —— 不同的对象不竞争
静态方法锁 类.class  —— 全类只有一把，对象再多也得等
普通方法没锁         —— 独立执行，谁也不等
```
