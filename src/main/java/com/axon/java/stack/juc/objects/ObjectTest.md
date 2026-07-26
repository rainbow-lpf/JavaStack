# 🚨 **内存泄漏分析报告**

## ❌ **会造成内存泄漏！**

这段代码确实存在**严重的内存泄漏问题**，原因如下：

## 🔍 **问题分析**

### **1️⃣ 静态字段持有引用**
```java
private static TestB testB; // 静态字段，生命周期与类相同

public void setTestB(){
    this.testB = new TestB(); // ⚠️ 每次都创建新对象赋给静态字段
}
```

### **2️⃣ 非静态内部类的隐式引用**
```java
class TestB{ // ⚠️ 非静态内部类隐式持有外部类ObjectTest的引用
    private String bookName;
    private Integer bookPrice;
}
```

## 🧠 **内存泄漏机制**

```java
// 内存引用链分析
静态字段testB → 最后一个TestB实例 → 隐式引用最后一个ObjectTest实例

// 结果：最后创建的ObjectTest和TestB对象无法被GC回收！
```

## 🧪 **验证内存泄漏的测试代码**

```java
public class MemoryLeakTest {
    
    @Test
    public void testMemoryLeak() {
        // 记录初始内存
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // 执行原代码逻辑
        for (int i = 0; i < 10000; i++) {
            ObjectTest objectTest = new ObjectTest();
            objectTest.setName("张三" + i);
            objectTest.setAge("18");
            objectTest.setTestB();
        }
        
        // 强制GC后检查内存
        runtime.gc();
        Thread.sleep(1000); // 等待GC完成
        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("内存增长: " + (afterMemory - beforeMemory) + " bytes");
        // 🚨 会发现内存没有完全释放
    }
}
```

## ✅ **修复方案**

### **方案1: 将内部类改为静态内部类**
```java
@Data
public class ObjectTest {
    
    private static TestB testB;
    private String name;
    private String age;

    public void setTestB(){
        // 只保留最后一个引用，之前的会被GC回收
        ObjectTest.testB = new TestB();
        ObjectTest.testB.setBookName("java");
        ObjectTest.testB.setBookPrice(100);
    }

    // ✅ 改为静态内部类，不持有外部类引用
    @Data
    static class TestB {
        private String bookName;
        private Integer bookPrice;
    }
}
```

### **方案2: 使用实例字段替代静态字段**
```java
@Data
public class ObjectTest {
    
    // ✅ 改为实例字段
    private TestB testB;
    private String name;
    private String age;

    public void setTestB(){
        this.testB = new TestB();
        this.testB.setBookName("java");
        this.testB.setBookPrice(100);
    }

    // ✅ 静态内部类
    @Data
    static class TestB {
        private String bookName;
        private Integer bookPrice;
    }
}
```

### **方案3: 完全重构（推荐）**
```java
@Data
public class ObjectTest {
    
    private String name;
    private String age;
    private TestB testB; // 实例字段

    // ✅ 构造时初始化
    public ObjectTest() {
        this.testB = new TestB("java", 100);
    }

    // ✅ 独立的静态内部类
    @Data
    @AllArgsConstructor
    static class TestB {
        private String bookName;
        private Integer bookPrice;
    }

    public static void main(String[] args) {
        List<ObjectTest> objects = new ArrayList<>();
        
        for (int i = 0; i < 1000; i++) {
            ObjectTest objectTest = new ObjectTest();
            objectTest.setName("张三" + i);
            objectTest.setAge("18");
            objects.add(objectTest);
        }
        
        // 使用完后清理引用
        objects.clear();
        System.gc(); // 建议GC
    }
}
```

## 📊 **内存泄漏对比**

| 场景 | 是否泄漏 | 原因 |
|------|---------|------|
| **原代码** | ❌ 是 | 静态字段+非静态内部类 |
| **方案1** | ✅ 否 | 静态内部类不持有外部引用 |
| **方案2** | ✅ 否 | 实例字段随对象生命周期 |
| **方案3** | ✅ 否 | 完全解耦+主动清理 |

## 🎯 **最佳实践建议**

1. **避免静态字段持有复杂对象引用**
2. **内部类优先使用static修饰**
3. **及时清理不需要的对象引用**
4. **使用内存分析工具定期检测**

推荐使用**方案3**，代码更清晰且完全避免内存泄漏！🚀