package com.axon.java.stack.juc.StringTable;

/**
 * @author：liupengfei
 * @date：2025/7/31
 * @description：
 *
 * StringTable 字符串常量池
 * jdk 1.7 之前，字符串常量池在方法区中。即是永久代。
 * jdk 1.7 之后，字符串常量池在堆中。
 *
 */
public class StringTest01 {

    public static void main(String[] args) {

           demo01();

           demo02();

           demo03();

    }


    public  static void demo01(){

        String s1 = new String("a") + new String("b");  //在堆内存中创建一个， s1指向堆中的地址

        String intern = s1.intern();  //字符串常量池中没有，则创建一个，关联到s1的堆中的地址引用，并返回引用

        String s2 = "ab";  //此时字符串常量池中已经有，则直接返回引用

        System.out.println(s1 == s2); //ture   所以s1==s2 为true

        System.out.println(s2 == intern); //true  所以s2==intern 为true
    }

    public  static  void  demo02(){

        String s1=new String("ab"); // 在堆内存中创建一个， s1的指向堆中的地址

        String intern = s1.intern(); //字符串常量池中没有，则拷贝创建在字符串中创建一个，并返回字符串常量池的引用。

        String s2="ab"; //此时字符串常量池中已经有，则直接返回引用

        System.out.println(s1==s2); //false   所以s1==s2 为false， 因为s1是堆中的地址， s2是字符串常量池中的地址，所以不同。

        System.out.println(s2==intern); //true   所以s2==intern 为true，因为s2是字符串常量池中的地址， intern是字符串常量池中的地址，所以相同。

    }


    public  static  void  demo03(){

        String x="ab";  // 字符串常量池中没有，则创建一个。x 则是字符串常量池中地址

        String s1 = new String("a") + new String("b"); //在堆内存中创建一个， s1指向堆中的地址

        String intern = s1.intern(); //此时，字符串常量池中已经有了，则intern 指向字符串常量池中的地址

        String s2 = "ab"; //此时字符串常量池中已经有了，则s2 指向字符串常量池中的地址

        System.out.println(s1 == s2); //false  所以s1==s2 为false，因为s1是堆中的地址， s2是字符串常量池中的地址，所以不同。

        System.out.println(s2 == intern); //true  所以s2==intern 为true，因为s2是字符串常量池中的地址， intern是字符串常量池中的地址，所以相同。


    }
}
