package com.axon.java.stack.juc.lockUpgrade.objectDemo;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.*;


/**
 * 1.一个实例化对象多大？
 * 2. 一个对象中包含哪些信息？
 * 包含： 对象头、实例数据、对齐填充（为了保证8个字节的倍数）
 * 对象头中包含：
 * 对象标记（mark word）： 哈希码、 GC标记、GC次数、 同步锁标记、偏向锁持有者     哈希码、GC标记 、GC次数、 同步锁标记、 偏向锁持有者 。
 * 类元信息(类型指针)：
 * 长度：
 *
 * <p>
 * 测试中添加以下引用：
 * <dependency>
 * <groupId>org.openjdk.jol</groupId>
 * <artifactId>jol-core</artifactId>
 * <version>0.17</version>
 * </dependency>
 * <p>
 * <p>
 * # WARNING: Unable to attach Serviceability Agent. You can try again with escalated privileges. Two options: a) use -Djol.tryWithSudo=true to try with sudo; b) echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
 * # Running 64-bit HotSpot VM.  // 表明JVM是64位的HotSpot虚拟机。
 * # Using compressed oop with 3-bit shift.      //JVM使用了压缩对象指针（Compressed Oops），这是为了减少指针占用的内存。3-bit shift表示对象指针在解压缩时会进行3位的移位操作。
 * # Using compressed klass with 3-bit shift.     //JVM使用了压缩类指针（Compressed Klass Pointers），类似于压缩对象指针，节省内存空间。
 * # WARNING | Compressed references base/shifts are guessed by the experiment!     //表示JOL工具基于实验性的猜测来确定压缩引用的基地址和移位操作。
 * # WARNING | Therefore, computed addresses are just guesses, and ARE NOT RELIABLE.    // 计算出的内存地址只是推测，可能不准确。
 * # WARNING | Make sure to attach Serviceability Agent to get the reliable addresses.  //建议附加Serviceability Agent以获得更可靠的内存地址信息。
 * # Objects are 8 bytes aligned.                                                   //对象在内存中是按照8字节对齐的。即每个对象的起始地址都将是8的倍数，这样做是为了提高内存访问的效率。
 * # Field sizes by type: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]
 * # Array element sizes: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]
 * <p>
 * <p>
 * //Field sizes by type：列出了不同类型字段在对象中占用的字节数，按顺序可能代表以下字段类型的大小：
 * •	4 字节：如 int、float
 * •	1 字节：如 boolean、byte
 * •	2 字节：如 char、short
 * •	8 字节：如 long、double
 * <p>
 * //Array element sizes：列出了数组元素的大小，按顺序对应不同类型的数组元素大小。例如：
 * •	int[] 或 float[] 数组中的每个元素占用 4 字节
 * •	boolean[] 或 byte[] 数组中的每个元素占用 1 字节
 * •	long[] 或 double[] 数组中的每个元素占用 8 字节
 * <p>
 * <p>
 * <p>
 * System.out.println("以下是输出的对象信息-------------");
 * Object o=new Object();
 * System.out.println(ClassLayout.parseInstance(o).toPrintable());
 * <p>
 * <p>
 * 以下是输出的对象信息-------------
 * java.lang.Object object internals:
 * OFFSET  SIZE   TYPE DESCRIPTION                               VALUE
 * 0     4        (object header)                           01 00 00 00 (00000001 00000000 00000000 00000000) (1)
 * 4     4        (object header)                           00 00 00 00 (00000000 00000000 00000000 00000000) (0)
 * 8     4        (object header)                           e5 01 00 f8 (11100101 00000001 00000000 11111000) (-134217243)
 * 12     4        (loss due to the next object alignment)
 * Instance size: 16 bytes                                              //这个java.lang.Object实例在内存中总共占用16字节。这16字节包括对象头和对齐填充。
 * Space losses: 0 bytes internal + 4 bytes external = 4 bytes total        //  表示由于内存对齐造成的浪费。这里有4字节的外部对齐损失（External Space Loss），没有内部损失（Internal Space Loss）。
 * <p>
 * <p>
 * 1. 对象的内存布局
 * OFFSET: 字段的内存偏移量（单位：字节），表示该字段相对于对象起始地址的偏移。
 * •	SIZE: 字段占用的字节数。
 * •	TYPE: 字段的数据类型。
 * •	DESCRIPTION: 字段的描述信息。
 * •	VALUE: 字段的实际值。
 * <p>
 * 2. 对象头信息
 * Java对象头通常包含三个部分：
 * 1.	Mark Word: 存储对象的哈希码、GC分代年龄、锁信息等。一般情况下是8字节，但在32位JVM中会被压缩成4字节。
 * 2.	Class Pointer: 指向对象的类元数据，表示这个对象是哪个类的实例。通常也是4字节（在32位JVM中）。
 * 3.	Array Length: 如果对象是数组，还会包含数组长度信息，但这里展示的是java.lang.Object，所以没有数组长度字段。
 * <p>
 * 这里的三个对象头字段分别占用了4字节，总共12字节：
 * <p>
 * •	第一个字段（0字节偏移，4字节大小）表示Mark Word的一部分。
 * •	第二个字段（4字节偏移，4字节大小）通常用于存储Class Pointer。
 * •	第三个字段（8字节偏移，4字节大小）可能是Mark Word或Class Pointer的剩余部分或其他对象头信息。
 * <p>
 * <p>
 * 3. 对齐损失 (Padding)
 * Padding: 由于JVM要求对象在内存中按一定字节数对齐，这里是8字节对齐，因此占用了4个字节来填充，使得对象大小达到8的倍数（16字节）。
 *
 *
 * //System.out.println("以下是自定义的输出的对象信息-------------");
 *  Customer customer = new Customer();
 *  System.out.println(ClassLayout.parseInstance(customer).toPrintable());
 *
 *  以下是自定义的输出的对象信息-------------
 * com.axon.java.stack.juc.lockUpgrade.objectDemo.Customer object internals:
 *  OFFSET  SIZE      TYPE DESCRIPTION                               VALUE
 *       0     4           (object header)                           01 00 00 00 (00000001 00000000 00000000 00000000) (1)        01: 无锁   101：偏向锁： 00: 轻量级锁   10：重量级别锁    11：垃圾回收标志
 *       4     4           (object header)                           00 00 00 00 (00000000 00000000 00000000 00000000) (0)
 *       8     4           (object header)                           8d f1 00 f8 (10001101 11110001 00000000 11111000) (-134155891)
 *      12     4       int Customer.id                               0
 *      16     1   boolean Customer.flag                             false
 *      17     7           (loss due to the next object alignment)
 * Instance size: 24 bytes
 * Space losses: 0 bytes internal + 7 bytes external = 7 bytes total
 *
 * 锁升级的流程：
 *  比如线程A、B、C、D 三个线程
 *  1.当目前只有A线程获得锁之后，则将对象头的中偏向锁线程id设置为当前线程，没有其它线程存在竞争
 *  2.此时B线程进来，也获取锁，那B是通过CAS机制，去获取设置偏向锁id,设置时，得等到线程A执行完，释放之后，才能设置。
 *      设置成功：线程B设置成功之后，那就将当前对象头偏向锁线程id设置为B，此时A就在外部不断自旋继续设置
 *      设置失败：线程B通过CAS设置失败了, 那就会将当前对象头偏向锁标识更新为轻量级锁，但是还是线程A在执行处理。
 *  3.此时如果线程C、D进来之后，都在外部通过CAS自旋，当自旋到一定次数后，会转换为重量级锁，将对象头的锁指针指向堆中moniter
 *     自旋次数：java6之前，10次或大于cpu的核数
 *             java6之后，通过算法处理， 如果自旋获得成功，那就可能下次自旋成功的概率就高，就继续自旋。如果失败，那自旋的失败率就高，那就可能转换为重量级锁。
 *
 *  4.java15之后，取消了偏向锁，默认关闭偏向锁
 *

 *
 *
 *
 *
 */
public class ObjectDemoTest {

    public static void main(String[] args) {

        //打印出VM 细节的详细情况
        System.out.println(VM.current().details());

        //所有对象分配的字节信息都是8的倍数
        System.out.println(VM.current().objectAlignment());


        System.out.println("以下默认的是输出的对象信息-------------");
        Object o = new Object();
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        System.out.println("以下是自定义的输出的对象信息-------------");
        Customer customer = new Customer();

        System.out.println(ClassLayout.parseInstance(customer).toPrintable());


    }
}


class Customer {

    private int id;

    private boolean flag;

}
