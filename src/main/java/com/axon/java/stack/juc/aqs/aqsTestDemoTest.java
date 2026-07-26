package com.axon.java.stack.juc.aqs;

import java.util.concurrent.locks.ReentrantLock;

/**
 * aqs底层原理解析
 *  AbstractQueuedSynchronizer          aqs队列同步器
 *  AbstractQueuedLongSynchronizer     long类型的aqs队列同步器
 *  AbstractOwnableSynchronizer
 *
 *  	AbstractQueuedSynchronizer (AQS) 是核心同步器，使用 int 类型的状态字段，并通过 FIFO 队列管理被阻塞的线程。
 * 	•	AbstractQueuedLongSynchronizer 是 AQS 的扩展版本，使用 long 类型的状态字段，适用于需要更大状态范围的情况。
 * 	•	AbstractOwnableSynchronizer 主要用于管理锁的所有权，而不涉及队列和状态的管理，是 AQS 的基础类。
 *
 *
 *  公平锁：   FairSync  公平锁的实现
 *  非公平锁：  NonfairSync  非公平锁的实现
 *
 *  AQS的核心步骤：
 *      1.尝试抢锁，抢锁成功或失败
 *      2.抢锁是失败，则加入双向环形队列中。
 *      3.针对抢锁失败，加入的队列的线程，执行挂起操作，即阻塞状态， LockSupport.park(this)
 *
 * 核心代码步骤：
 *
 * 举例说明， 假如有线程A、B去同时争抢锁， 线程A已经抢到锁，且处理需要耗时很久。
 *
 *  final void lock() {
 *             if (compareAndSetState(0, 1))  // 线程进来尝试设置状态位，将状态为设置为1。 （即A线程进入，设置状态位， 设置成功）
 *                 setExclusiveOwnerThread(Thread.currentThread());  // 设置线程持有者是当前线程   （即设置线程持有者是A线程）
 *             else
 *                 acquire(1);   //再次尝试抢占锁，抢占失败，则加入队列   (即B线程进入后，发现状态位已被修改为1，则进入该步骤)
 *         }
 *
 *     public final void acquire(int arg) {
 *         if (!tryAcquire(arg) &&   // 再次尝试争抢锁
 *             acquireQueued(addWaiter(Node.EXCLUSIVE), arg))    // 加入队列挂起线程
 *             selfInterrupt();
 *     }
 *
 *
 *  protected final boolean tryAcquire(int acquires) {
 *             final Thread current = Thread.currentThread();   // 获取当前线程
 *             int c = getState();  //获取状态位
 *             if (c == 0) {       // 判断当前状态为位是否等于0 ，
 *                 if (!hasQueuedPredecessors() &&
 *                     compareAndSetState(0, acquires)) {
 *                     setExclusiveOwnerThread(current);  //设置当前的线程持有者
 *                     return true;
 *                 }
 *             }
 *             else if (current == getExclusiveOwnerThread()) {     // 判断线程持有者是不是当前线程，  这内部的逻辑相当于可重入锁的逻辑
 *                 int nextc = c + acquires;   状态位加1
 *                 if (nextc < 0)
 *                     throw new Error("Maximum lock count exceeded");
 *                 setState(nextc);  // 设置状态位，
 *                 return true;      //  返回true，表名获取锁成功
 *             }
 *             return false;
 *         }
 *
 *     private Node addWaiter(Node mode) {
 *         Node node = new Node(Thread.currentThread(), mode);  // 初始化一个节点
 *         // Try the fast path of enq; backup to full enq on failure
 *         Node pred = tail;   //判断尾节点是否等于空
 *         if (pred != null) {  // 尾结点不为空，说明已存队列。
 *             node.prev = pred;     //将当前节点的前指针的指向尾结点            （线程C进入后的设置）
 *             if (compareAndSetTail(pred, node)) {   // 将当前节点设置为尾节点     （将线程C设置为尾结点）
 *                 pred.next = node;  //将上一节点的后指针指向当前节点 。            （即将上一节点的后指针指向线程C）
 *                 return node;
 *             }
 *         }
 *         enq(node);  // 初始化节点    （B线程进入之后）
 *         return node;
 *     }
 *
 *
 *     private Node enq(final Node node) {
 *         for (;;) {
 *             Node t = tail;   // 获取尾节点
 *             if (t == null) { // Must initialize    判断尾节点是否为空，
 *                 if (compareAndSetHead(new Node()))    //如果为空，初始化一个头结点，   (线程B 首次进入之后的一个逻辑)
 *                     tail = head;  //并将头结点赋值尾结点。
 *             } else {
 *                 node.prev = t;         //将当前节点的前指针指向上个尾结点
 *                 if (compareAndSetTail(t, node)) {   // 将当前节点设置为 尾结点  （即线程B）
 *                     t.next = node;  将上个节点的后指针指向当前节点
 *                     return t;
 *                 }
 *             }
 *         }
 *
 *     final boolean acquireQueued(final Node node, int arg) {
 *         boolean failed = true;
 *         try {
 *             boolean interrupted = false;
 *             for (;;) {
 *                 final Node p = node.predecessor();    //自旋获取当前节点的上一节点，
 *                 if (p == head && tryAcquire(arg)) {   // 判断当前p是否等于头结点， 如果等于，说明已经排到队， 则继续尝试获取设置状态位， 即抢锁操作
 *                     setHead(node); // 如果 抢占成功， 则设置当前节点为头节点，
 *                     p.next = null; // help GC回收参数设置  // 设置当前节点的上一节点的后指针为空
 *                     failed = false;        //设置是否失败过
 *                     return interrupted;     // 返回
 *                 }
 *                 if (shouldParkAfterFailedAcquire(p, node) &&
 *                     parkAndCheckInterrupt())   // 挂起当前线程 ，LockSupport.park(this);
 *                     interrupted = true;   //没抢到锁之后的处理，  （即线程B）
 *             }
 *         } finally {
 *             if (failed)
 *                 cancelAcquire(node);
 *         }
 *     }
 *
 *   private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {     // pred 当前线程的上一节点，  node 指向当前线程节点
 *         int ws = pred.waitStatus;   // 初始化时是为0 ，  （线程B的上一节点是 哨兵节点， 初始值为0 ）
 *         if (ws == Node.SIGNAL)  //-1
 *
 *             return true;
 *         if (ws > 0) {
 *
 *             do {
 *                 node.prev = pred = pred.prev;
 *             } while (pred.waitStatus > 0);
 *             pred.next = node;
 *         } else {
 *             compareAndSetWaitStatus(pred, ws, Node.SIGNAL);     // 等于0 ， 则设置为-1， （即线程B的上一节点（哨兵节点）的状态设置为-1）
 *         }
 *         return false;
 *     }
 *
 *
 * // 释放锁逻辑
 *
 *     public final boolean release(int arg) {
 *         if (tryRelease(arg)) {    // 释放锁操作
 *             Node h = head;  // 获取头节点，
 *             if (h != null && h.waitStatus != 0)  // 判断当前头节点是否不等于空， 或者状态位不等于0
 *                 unparkSuccessor(h);  // 去唤醒头节点的下一节点， 传入进去的是头节点
 *             return true;
 *         }
 *         return false;
 *     }
 *
 *
 *    protected final boolean tryRelease(int releases) {
 *             int c = getState() - releases;    // 状态位减一
 *             if (Thread.currentThread() != getExclusiveOwnerThread())   // 判断当前线程是否等于线程持有者的线程
 *                 throw new IllegalMonitorStateException();
 *             boolean free = false;
 *             if (c == 0) {   // 等于0
 *                 free = true;
 *                 setExclusiveOwnerThread(null);  // 设置当前线程持有者的线程为null
 *             }
 *             setState(c);   // 设置状态位为0
 *             return free;
 *         }
 *
 *   private void unparkSuccessor(Node node) {
 *
 *         int ws = node.waitStatus;      获取头节点状态， 此时状态位-1
 *         if (ws < 0)
 *             compareAndSetWaitStatus(node, ws, 0);    //将头节点的状态设置为0
 *
 *
 *         Node s = node.next;  // 获取头结点的下一节点， （此时B线程的Node状态位等于0）
 *         if (s == null || s.waitStatus > 0) {
 *             s = null;
 *             for (AbstractQueuedSynchronizer.Node t = tail; t != null && t != node; t = t.prev)
 *                 if (t.waitStatus <= 0)
 *                     s = t;
 *         }
 *         if (s != null)
 *             LockSupport.unpark(s.thread);  // 将头节点的下一节点给 unpark处理。   (即B节点唤醒操作)
 *     }
 *
 * // 线程取消的操作
 *  private void cancelAcquire(Node node) {
 *         // Ignore if node doesn't exist
 *         if (node == null)
 *             return;
 *
 *         node.thread = null;
 *
 *         // Skip cancelled predecessors
 *         Node pred = node.prev;
 *         while (pred.waitStatus > 0)    // 判断当前节点的上一节点是否大于0  ，大于0 则是取消状态。   通过循环，将当前节点的 prev 字段向前跳过所有 waitStatus 大于 0（即已取消）的前驱节点。这样做是为了将当前节点连接到未取消的前驱节点上。
 *             node.prev = pred = pred.prev;
 *
 *         // predNext is the apparent node to unsplice. CASes below will
 *         // fail if not, in which case, we lost race vs another cancel
 *         // or signal, so no further action is necessary.
 *         Node predNext = pred.next;     // 记录前驱节点的下一个节点，保存前驱节点的 next 字段，即 predNext，这是当前节点的前驱节点的后继节点，稍后可能需要用到这个引用来更新链表结构。
 *
 *         // Can use unconditional write instead of CAS here.
 *         // After this atomic step, other Nodes can skip past us.
 *         // Before, we are free of interference from other threads.
 *         node.waitStatus = Node.CANCELLED;
 *
 *         // If we are the tail, remove ourselves.
 *         if (node == tail && compareAndSetTail(node, pred)) { //则尝试将前驱节点设置为新的尾节点，并将前驱节点的 next 字段设置为 null。这样就把当前节点从队列中移除了。
 *             compareAndSetNext(pred, predNext, null);
 *         } else {
 *             // If successor needs signal, try to set pred's next-link
 *             // so it will get one. Otherwise wake it up to propagate.
 *             int ws;                                                      // 这里是指中间的某些节点取消的曹操
 *             if (pred != head &&
 *                 ((ws = pred.waitStatus) == Node.SIGNAL ||
 *                  (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&   // 如果当前节点不是尾节点，就要处理链表中间节点的取消。首先检查前驱节点是否是 head，如果不是，则检查前驱节点的状态是否为 SIGNAL（表示需要通知后继节点）或 ws <= 0（非取消状态）。如果前驱节点需要通知后继节点（SIGNAL），那么尝试设置其 next 字段，使其跳过已取消的节点，直接指向当前节点的后继节点。
 *                 pred.thread != null) {
 *                 Node next = node.next;
 *                 if (next != null && next.waitStatus <= 0)
 *                     compareAndSetNext(pred, predNext, next);   //去设置下一个节点。
 *             } else {
 *                 unparkSuccessor(node);   // 如果前驱节点是头节点（head），则通过 unparkSuccessor(node) 方法来唤醒当前节点的后继节点，以继续争抢锁。
 *             }
 *
 *             node.next = node; // help GC回收参数设置
 *         }
 *     }
 *
 *
 *
 *
 *
 *
 */
public class aqsTestDemoTest {

    /**
     *  假如有线程A、B去同时争抢锁， 线程A已经抢到锁，且处理需要耗时很久。 AQS具体逻辑是怎样的？
     *
     *  1.线程A 已经抢到锁，状态位已设置为1， 当线程持有者的是线程A
     *  2.线程B首先去尝试争抢锁， 那肯定失败， 因为A还在处理中。
     *  3.此时，线程B则去需要加入队列。
     *  4.加入队列时，发现头、尾都为空， 那此时会初始化一个哨兵Node。
     *  5.继续将线程B加入队列， 将线程B Node 的前指针指向哨兵Node， 将哨兵Node的next节点指向B, 将尾节点设置为B的Node。
     *  6.线程B加入环形队列后， 将哨兵节点的 waitStatus设置为-1， 方便后续唤起B
     *  7.使用LockSupport.park(this)挂起线程B
     *
     *  8.当线程A执行完之后，去释放锁，
     *  9.线程A将状态位设置为0 ,将线程持有者设置为null
     *  10.去获取头节点， 去设置头节点的状态 waitStatus为0
     *  11.获取头节点的next节点， 进行 使用LockSupport.unpark(next）操作。
     *
     *  12.唤醒之后，线程B则去争抢锁， 假如抢到锁了。
     *  13.设置线程B的节点为头节点。
     *  14.原来的哨兵节点的next 为null，则剔除队列。
     *
     *
     *  假设 线程A 和线程B 去同时争
     *  chatGPT  解释：
     *
     *  1.	AQS 状态表示和初始状态：
     * 	•	    AQS 状态：AQS 内部有一个整数字段 state 来表示锁的状态。当 state == 0 时表示锁未被占用，当 state > 0 时表示锁已被占用。此时 state 的值通常代表重入锁的次数。
     * 	•	    初始状态：在锁还未被任何线程持有时，state 的初始值为 0。
     * 	2.	线程 A 抢到锁：
     * 	•	    线程 A 抢到锁后，将 state 设置为 1，并将当前线程（线程 A）设置为锁的持有者。这时，AQS 的 exclusiveOwnerThread 字段会指向线程 A。
     * 	3.	线程 B 尝试获取锁：
     * 	•	    当线程 B 尝试获取锁时，会首先检查 state 的值。发现 state 已经不为 0（即锁已被占用），于是线程 B 进入 AQS 的阻塞队列等待锁释放。
     * 	•	    线程 B 加入阻塞队列时，如果队列为空，AQS 会创建一个哨兵节点（头节点，通常称为 dummy node），这个节点本身不存储任何线程，只是用作队列的头。
     * 	•	    线程 B 会被添加到哨兵节点之后，成为队列中的第一个真实节点。
     * 	4.	线程 B 挂起：
     * 	•	    线程 B 被添加到队列后，会调用 LockSupport.park(this) 挂起自己，等待被唤醒。
     * 	5.	线程 A 释放锁：
     * 	•	    当线程 A 处理完成后，会调用 unlock() 方法释放锁。unlock() 方法会将 state 设置为 0，并将 exclusiveOwnerThread 设置为 null。
     * 	•	    然后 AQS 会检查等待队列中是否有其他线程在等待锁。如果有，它会唤醒队列中的第一个节点（即线程 B 对应的节点）。
     * 	6.	线程 B 被唤醒并重新获取锁：
     * 	•	    线程 B 被唤醒后，会重新尝试获取锁。如果成功，state 会被设置为 1，并且 exclusiveOwnerThread 设置为线程 B。
     * 	•	    此时，线程 B 的节点会成为新的头节点，旧的哨兵节点则被剔除。
     * 	7.	清理队列：
     * 	•	    当线程 B 成为新的头节点后，AQS 会清理队列中不再需要的节点，确保队列结构的完整性。
     *
     * @param args
     */
   private static ReentrantLock lock = new ReentrantLock();

   private static volatile boolean flag=true;


    public static void main(String[] args) throws InterruptedException {

        new Thread(() -> {
            lock.lock();
            try {
                System.out.println("线程A 已经来了， 正在处理中。");
                while(flag){

                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

        },"AAA").start();

        Thread.sleep(1000);  // 这里睡10s,优先让线程A抢到锁


        new Thread(() -> {
            lock.lock();
            try {
                System.out.println("线程B 已经来了， 正在处理中。");
                for (int i = 0; i < 1000; i++) {

                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

        },"AAA").start();


        Thread.sleep(1000*60);
        flag=false;
        System.out.println("运行结束");

    }






}
