package com.axon.java.stack.juc.objects;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * @author：liupengfei
 * @date：2025/8/4
 * @description：finalize方法使用案例演示
 */
public class ObjectFinalizeTest {

    private static List<WeakReference<ObjectFinalizeTest>> references = new ArrayList<>();
    private static int createCount = 0;
    private static int finalizeCount = 0;

    private String name;
    private FileOutputStream fileStream;
    private boolean isFinalized = false;

    public ObjectFinalizeTest(String name) {
        this.name = name;
        createCount++;
        System.out.println("📦 创建对象: " + name + " (总创建数: " + createCount + ")");

        // 模拟持有资源
        try {
            this.fileStream = new FileOutputStream("temp_" + name + ".txt");
        } catch (IOException e) {
            System.err.println("❌ 文件创建失败: " + e.getMessage());
        }

        // 添加弱引用用于跟踪
        references.add(new WeakReference<>(this));
    }

    /**
     * 🚀 开始finalize方法演示
     *
     * === 案例1: 基本finalize使用 ===
     * 📦 创建对象: Basic-1 (总创建数: 1)
     * 📦 创建对象: Basic-2 (总创建数: 2)
     * 📦 创建对象: Basic-3 (总创建数: 3)
     * 🗑️ 调用System.gc()...
     * 🧹 finalize被调用: Basic-2 (调用线程: Finalizer, 总调用数: 1)
     *   └─ 💾 文件流已关闭: temp_Basic-2.txt
     *   └─ 🔧 清理其他资源完成
     * 🧹 finalize被调用: Basic-1 (调用线程: Finalizer, 总调用数: 2)
     *   └─ 💾 文件流已关闭: temp_Basic-1.txt
     *   └─ 🔧 清理其他资源完成
     * 案例1完成
     * @param args
     */
    public static void main(String[] args) {
        System.out.println("🚀 开始finalize方法演示\n");

        // 案例1: 基本finalize使用
        demonstrateBasicFinalize();

        // 案例2: 资源清理演示
        demonstrateResourceCleanup();

        // 案例3: finalize的不可靠性
        demonstrateUnreliability();

        // 案例4: 对象复活演示
        demonstrateObjectResurrection();

        // 最终统计
        System.out.println("\n📊 最终统计:");
        System.out.println("创建对象数: " + createCount);
        System.out.println("finalize调用数: " + finalizeCount);

        // 清理弱引用
        long aliveObjects = references.stream()
                                      .mapToLong(ref -> ref.get() != null ? 1 : 0)
                                      .sum();
        System.out.println("存活对象数: " + aliveObjects);
    }

    /**
     * 案例1: 基本finalize使用演示
     */
    private static void demonstrateBasicFinalize() {
        System.out.println("=== 案例1: 基本finalize使用 ===");

        // 创建对象并立即失去引用
        new ObjectFinalizeTest("Basic-1");
        new ObjectFinalizeTest("Basic-2");
        new ObjectFinalizeTest("Basic-3");

        // 强制垃圾回收
        System.out.println("🗑️ 调用System.gc()...");
        System.gc();

        // 等待finalize执行
        try {
            Thread.sleep(1000);
            System.runFinalization(); // 建议JVM执行finalize方法
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("案例1完成\n");
    }

    /**
     * 案例2: 资源清理演示
     */
    private static void demonstrateResourceCleanup() {
        System.out.println("=== 案例2: 资源清理演示 ===");

        try {
            ObjectFinalizeTest obj = new ObjectFinalizeTest("Resource-1");
            // 模拟使用资源
            if (obj.fileStream != null) {
                obj.fileStream.write("Hello World".getBytes());
                System.out.println("✅ 写入数据到文件");
            }

            // 故意不关闭资源，让finalize来处理
            obj = null; // 失去引用

        } catch (IOException e) {
            System.err.println("❌ IO操作失败: " + e.getMessage());
        }

        // 触发GC
        System.gc();
        System.runFinalization();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("案例2完成\n");
    }

    /**
     * 案例3: finalize的不可靠性演示
     */
    private static void demonstrateUnreliability() {
        System.out.println("=== 案例3: finalize不可靠性演示 ===");

        // 创建大量对象
        for (int i = 0; i < 10; i++) {
            new ObjectFinalizeTest("Unreliable-" + i);
        }

        System.out.println("⏰ 等待3秒观察finalize调用情况...");
        System.gc();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("⚠️ 注意: 并非所有对象的finalize都被调用");
        System.out.println("案例3完成\n");
    }

    /**
     * 案例4: 对象复活演示
     */
    private static ObjectFinalizeTest resurrectObj;

    private static void demonstrateObjectResurrection() {
        System.out.println("=== 案例4: 对象复活演示 ===");

        ResurrectableObject obj = new ResurrectableObject("Phoenix");
        obj = null; // 失去引用

        System.gc();
        System.runFinalization();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 检查对象是否复活
        if (ResurrectableObject.resurrectedObj != null) {
            System.out.println("🔥 对象成功复活: " + ResurrectableObject.resurrectedObj.name);

            // 再次设为null，这次真正死亡
            ResurrectableObject.resurrectedObj = null;
            System.gc();
            System.runFinalization();

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("案例4完成\n");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!isFinalized) {
                finalizeCount++;
                isFinalized = true;

                System.out.println("🧹 finalize被调用: " + name +
                                   " (调用线程: " + Thread.currentThread().getName() +
                                   ", 总调用数: " + finalizeCount + ")");

                // 清理资源
                if (fileStream != null) {
                    try {
                        fileStream.close();
                        System.out.println("  └─ 💾 文件流已关闭: temp_" + name + ".txt");
                    } catch (IOException e) {
                        System.err.println("  └─ ❌ 关闭文件流失败: " + e.getMessage());
                    }
                }

                // 模拟清理其他资源
                System.out.println("  └─ 🔧 清理其他资源完成");
            }
        } finally {
            // 必须调用父类的finalize
            super.finalize();
        }
    }

    /**
     * 可复活的对象类
     */
    static class ResurrectableObject {
        static ResurrectableObject resurrectedObj;
        private String name;

        public ResurrectableObject(String name) {
            this.name = name;
            System.out.println("🔮 创建可复活对象: " + name);
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                System.out.println("💀 " + name + " 即将死亡，尝试复活...");
                // 对象复活：重新建立强引用
                resurrectedObj = this;
                System.out.println("✨ " + name + " 复活成功！");
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * 更好的资源管理方式示例
     */
    static class BetterResourceManagement implements AutoCloseable {
        private FileOutputStream fileStream;
        private String name;

        public BetterResourceManagement(String name) throws IOException {
            this.name = name;
            this.fileStream = new FileOutputStream("better_" + name + ".txt");
            System.out.println("✅ 创建资源: " + name);
        }

        @Override
        public void close() throws IOException {
            if (fileStream != null) {
                fileStream.close();
                System.out.println("✅ 正确关闭资源: " + name);
                fileStream = null;
            }
        }

        // 演示try-with-resources的正确用法
        public static void demonstrateProperResourceManagement() {
            System.out.println("\n=== 💡 推荐的资源管理方式 ===");

            try (BetterResourceManagement resource =
                         new BetterResourceManagement("AutoClose")) {
                // 使用资源
                resource.fileStream.write("Proper resource management".getBytes());
                System.out.println("✅ 资源使用完成");
            } catch (IOException e) {
                System.err.println("❌ 资源操作失败: " + e.getMessage());
            }

            System.out.println("✅ 资源自动关闭，无需依赖finalize");
        }
    }



}