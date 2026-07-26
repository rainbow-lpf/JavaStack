package com.axon.java.stack.juc.八锁案例;

import java.util.concurrent.TimeUnit;

class Phone {

    public static synchronized void sendEmail() {
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        System.out.println("-------sendEmail");
    }

    public static synchronized void sendSms() {
        System.out.println("-------sendSms");
    }

    public void hello() {
        System.out.println("----hello");
    }
}

/**
 * 8锁案例：
 * 1.标准访问有ab两个线程，请问先打印邮件还是短信？
 * 2.sendEmail 方法中加入暂停3秒钟，请问先打印邮件还是短信
 * 3.添加一个普通的hello的方法，请问先邮件还是hello ?
 * 4.有两部手机，请问先打印邮件还是短信？
 * 5.有两个静态同步方法，一步手机，请问先打印邮件还是短信？
 * 6.有两个静态同步方法，两部手机，请问先打印邮件还是短信？
 * 7.有1个静态同步方法，有一个普通方法，有1部手机，请问先打印邮件还是短信？
 * 8.有1个静态同步方法，有1个普通的同步方法，有2部手机，请问先打印邮件还是短信？
 *
 * 笔记总结：
 * 1-2 一个对象里面如果有多个sync方法，某一个时刻内，只要有一个线程调用其中的一个sync方法了，其它线程都只能等待，换句话说，
 * 某一时刻内，只能有唯一的一个线程去访问nsyc 锁的是的昂起对象this .  其它的新城都不能进去当前对象的其他的sync方法。
 */
public class Lock8Demo {

    public static void main(String[] args) {
        Phone phone = new Phone();
        Phone phone2 = new Phone();


        new Thread(() -> {
            //phone.sendEmail();
            phone.sendEmail();
        }, "aaaa").start();

        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        new Thread(() -> {
            // phone.sendSms();
            //phone.hello();
            //phone2.sendSms();
            //phone.sendSms();
            phone2.sendSms();
        }, "bbbb").start();

    }


}
