package com.axon.java.stack.juc.StringTable;

/**
 * @author：liupengfei
 * @date：2025/7/31
 * @description：
 */
public class StringTableGpt {


    public static void main(String[] args) {
        demo01();

        demo02();

        demo03();
    }

    /**
     * // JDK 1.6 及之前的行为
     * String s1 = new String("a") + new String("b");
     * String intern = s1.intern(); // 在常量池中创建新的"ab"拷贝
     * // s1 != intern （不同对象）
     *
     * // JDK 1.7+ 的行为
     * String s1 = new String("a") + new String("b");
     * String intern = s1.intern(); // 在常量池中存储s1的引用
     * // s1 == intern （同一对象）
     */
    public static void demo01(){
        // 🎯 关键：通过字符串拼接创建，不会自动进入常量池
        String s1 = new String("a") + new String("b");  // 在堆中创建"ab"对象，s1指向堆地址

        // 🔑 JDK1.7+: 常量池中没有"ab"，将s1指向的堆对象引用存入常量池
        String intern = s1.intern();  // 返回s1的引用（指向同一个堆对象）

        // 📋 常量池中已有"ab"引用，直接返回（指向s1的堆对象）
        String s2 = "ab";

        System.out.println(s1 == s2);     // true - 都指向同一个堆对象
        System.out.println(s2 == intern); // true - 都指向同一个堆对象
    }

    public static void demo02(){
        // ⚠️ 重要：new String("ab") 会同时创建两个对象！
        // 1. 字面量"ab"进入常量池  2. new String()在堆中创建对象
        String s1 = new String("ab"); // s1指向堆中的String对象

        // 🔑 常量池中已经有"ab"（字面量创建的），直接返回常量池引用
        String intern = s1.intern(); // 返回常量池中的"ab"引用

        // 📋 返回常量池中已有的"ab"引用
        String s2 = "ab";

        System.out.println(s1 == s2);     // false - s1指向堆，s2指向常量池
        System.out.println(s2 == intern); // true - 都指向常量池
    }

    public static void demo03(){
        // 📋 在常量池中创建"ab"
        String x = "ab";

        // 🎯 在堆中创建"ab"对象（拼接方式不会自动进入常量池）
        String s1 = new String("a") + new String("b");

        // 🔑 常量池中已有"ab"，返回常量池引用
        String intern = s1.intern();

        // 📋 返回常量池中的"ab"引用
        String s2 = "ab";

        System.out.println(s1 == s2);     // false - s1指向堆，s2指向常量池
        System.out.println(s2 == intern); // true - 都指向常量池
    }




}
