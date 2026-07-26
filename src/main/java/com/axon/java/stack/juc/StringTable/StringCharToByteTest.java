package com.axon.java.stack.juc.StringTable;

/**
 * @author：liupengfei
 * @date：2025/8/1
 * @description：
 */
public class StringCharToByteTest {

    public static void main(String[] args) {

        demonstrateJava8StringMemory();

        demonstrateCompactStringMemory();
    }

    // 演示内存使用
    public static void demonstrateJava8StringMemory() {
        String ascii = new String("Hello");      // 5个字符 × 2字节 = 10字节
        String chinese = new String("你好世界");   // 4个字符 × 2字节 = 8字节

        System.out.println("=== Java 8 String内存使用 ===");
        System.out.println("ASCII字符串 'Hello': " + ascii.length() * 2 + " 字节");
        System.out.println("中文字符串 '你好世界': " + chinese.length() * 2 + " 字节");
        System.out.println("💡 所有字符都占用2字节，存在内存浪费");
    }

    // 演示紧凑字符串内存优化
    public static void demonstrateCompactStringMemory() {
        System.out.println("=== Java 9+ 紧凑字符串内存使用 ===");

        // ASCII字符串 - 使用LATIN1编码
        String ascii = new String("Hello");
        System.out.println("ASCII字符串 'Hello':");
        System.out.println("  实际存储: " + ascii.length() + " 字节 (LATIN1编码)");
        System.out.println("  节约内存: " + ascii.length() + " 字节 (50%节省)");

        // 包含非ASCII字符的字符串 - 使用UTF16编码
        String mixed = new String("Hello世界");
        System.out.println("混合字符串 'Hello世界':");
        System.out.println("  实际存储: " + mixed.length() * 2 + " 字节 (UTF16编码)");
        System.out.println("  编码选择: 自动检测到非ASCII字符，使用UTF16");

        // 纯中文字符串 - 使用UTF16编码
        String chinese = new String("你好世界");
        System.out.println("中文字符串 '你好世界':");
        System.out.println("  实际存储: " + chinese.length() * 2 + " 字节 (UTF16编码)");
        System.out.println("  编码选择: 非ASCII字符，必须使用UTF16");
    }


}
