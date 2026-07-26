package com.axon.java.stack.juc.Volatile;

/**
 * 禁止指令重排序
 */

class MyDataSort {
    int numbers = 5;
    volatile boolean flag = false;


    public void method1() {
        numbers = 6;
        flag = true;  // 这个步骤可能出现指令重排序， 导致    flag = true;  先运行，然后再运行 numbers = 6;  从而导致 method2中  numbers的值numbers=5+5的情况 ，所以使用 Volatile 会接种这种情况
    }

    public void method2() {
        if (flag) {
            numbers = numbers + 5;
            System.out.println("numbers的值是" + numbers);
        }
    }
}


public class VolatileSortTest {

    public static void main(String[] args) {

        MyDataSort myData = new MyDataSort();

        new Thread(() -> {
            myData.method1();
        }, "AAA").start();
        myData.method2();

        new Thread(() -> {
            myData.method2();
        }, "BBB").start();

        System.out.println("运行结束");


    }
}
