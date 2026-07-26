package com.axon.java.stack.juc.objects;

import lombok.Data;

/**
 * @author：liupengfei
 * @date：2025/8/4
 * @description：
 *
 * 内存泄漏案例
 *
 */
@Data
public class ObjectTest extends Object{

    private static TestB  testB;   // 这样的写法会造成严重的内存泄漏， 因为static 修饰的变量，不会被垃圾回收，会与类的生命周期一致。

    private String name;

    private String age;

    public  void  setTestB(){
        this.testB = new TestB();
        this.testB.setBookName("java");
        this.testB.setBookPrice(100);
    }
    public static void main(String[] args) {

        for (int i = 0; i < 1000; i++) {
            ObjectTest objectTest = new ObjectTest();
            objectTest.setName("张三");
            objectTest.setAge("18");
            objectTest.setTestB();
        }


    }


    @Data
    class  TestB{

        private  String bookName;

        private  Integer bookPrice;

    }

}
