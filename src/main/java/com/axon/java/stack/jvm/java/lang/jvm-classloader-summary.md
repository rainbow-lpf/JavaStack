# 类加载器 & 双亲委派机制 — 面试总结

> 目录：`com.axon.java.stack.jvm.java.lang`

---

## 一、案例：自定义 String 类为什么跑不起来？

```java
// String.java
package java.lang;

public class String {
    public static void main(String[] args) {
        System.out.println("hello ");
    }
}
```

**结果：** 编译通过，运行时 `java.lang.SecurityException: Prohibited package name: java.lang` 或找不到 main 方法。

**原因：** JVM 的类加载器按照双亲委派模型加载。当要加载 `java.lang.String` 时，启动类加载器（Bootstrap ClassLoader）先出手，在 `rt.jar` 中找到官方的 `java.lang.String` 并加载。根本轮不到你的自定义 `String` 类。

---

## 二、双亲委派机制

### 一句话

> **类加载时，先向上委托父加载器，父加载器能加载就父先来，父加载不了才自己上。**

### 三层类加载器

```
       启动类加载器（Bootstrap ClassLoader）
            ↑ 父
       扩展类加载器（Extension ClassLoader）     JDK 9+ 改名为 Platform ClassLoader
            ↑ 父
       应用程序类加载器（Application ClassLoader）
```

| 加载器 | 加载路径 | 加载内容 |
|--------|---------|------|
| **启动类加载器**（Bootstrap） | `<JAVA_HOME>/lib`（rt.jar 等） | Java 核心类：`java.lang.*`、`java.util.*` |
| **扩展类加载器**（Extension） | `<JAVA_HOME>/lib/ext` | 扩展类 |
| **应用程序类加载器**（App） | `classpath` | 用户自己写的类 |

### 加载流程

```
需要加载 com.axon.User 类
        │
        ▼
应用程序加载器：先问爸——扩展加载器你能加载不？
        │
        ▼
扩展加载器：先问爸——启动加载器你能加载不？
        │
        ▼
启动加载器：我找找... 不在 rt.jar 里，我加载不了！退回
        │
        ▼
扩展加载器：我来找找... 也不在我这，退回
        │
        ▼
应用程序加载器：那我自己来！从 classpath 加载
```

### 核心源码

```java
// ClassLoader.loadClass() 简化版
protected Class<?> loadClass(String name, boolean resolve) {
    synchronized (getClassLoadingLock(name)) {
        // 1. 检查是否已经加载过
        Class<?> c = findLoadedClass(name);

        if (c == null) {
            // 2. 先委托父加载器
            if (parent != null) {
                c = parent.loadClass(name, false);
            } else {
                c = findBootstrapClassOrNull(name);  // 启动类加载器
            }

            // 3. 父加载不了，自己上
            if (c == null) {
                c = findClass(name);
            }
        }
        return c;
    }
}
```

---

## 三、为什么设计双亲委派？

| 作用 | 说明 |
|------|------|
| **沙箱安全** | 防止自定义类污染 Java 核心源码。你写个 `java.lang.String`，永远轮不到你加载 |
| **避免重复加载** | 父加载过的类，子不再加载。保证一个类的唯一性 |
| **保证核心类安全** | `java.lang.Object`、`String` 等只能由启动类加载器加载 |

---

## 四、类加载的三个阶段

```
加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载
│      │     │      │      │
│      │     │      │      └→ 执行 static 代码块、静态变量赋值
│      │     │      └→ 符号引用 → 直接引用
│      │     └→ 分配内存，给静态变量赋默认值（0/null）
│      └→ 校验 class 文件格式、字节码
└→ 从 class 文件或 jar 读取二进制流 → Class 对象
```

---

## 五、打破双亲委派

| 方式 | 案例 |
|------|------|
| 重写 `findClass()` | 自定义类加载器，不从父加载 |
| SPI 机制 | JDBC 驱动——`DriverManager` 在 rt.jar（Bootstrap 加载），但具体驱动在 classpath。用**线程上下文类加载器**（Thread Context ClassLoader）打破 |
| Tomcat 类加载 | 每个 Web 应用独立加载，避免应用间冲突 |

---

## 六、面试话术

### 双亲委派（30 秒版）

> **JVM 有三层类加载器：** Bootstrap（加载 rt.jar）、Extension（加载 ext）、Application（加载 classpath）。
>
> **双亲委派：** 类加载请求先向上交给父加载器，父加载得了就父来，父加载不了才自己上。
>
> **目的：** 沙箱安全——防止自定义类污染核心源码（如你写 `java.lang.String` 永远加载不到）；避免重复加载。
>
> **打破：** SPI 用线程上下文类加载器、Tomcat 自定义 WebAppClassLoader 各自隔离。
