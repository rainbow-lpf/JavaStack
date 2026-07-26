package com.axon.java.stack.juc.StringTable;

/**
 * @author：liupengfei
 * @date：2025/7/31
 * @description：
 */
public class StringTest02 {
    public static void main(String[] args) {

        demo04();
    }


    private final  static String  fin = "a";

    private static  String x="a";

    public static void demo04(){

        String s1=fin+"b";

        String s2="a"+"b";

        String s3=x+"b";

        System.out.println(s1==s2);

        System.out.println(s2==s3);

        System.out.println(s1==s3);

    }
}
