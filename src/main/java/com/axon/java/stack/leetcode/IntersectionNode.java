package com.axon.java.stack.leetcode;

import lombok.Data;

/**
 *  相交链表
 * @author：liupengfei
 * @date：2025/8/20
 * @description：
 */
public class IntersectionNode {

    @Data
    public static class ListNode {
        int val;
        ListNode next;
        ListNode(int x) {
            val = x;
            next = null;
        }
    }

    /**
     *  相交链表算法
     */

    public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
        if (headA == null || headB == null) {
            return null;
        }

        ListNode pA = headA;

        ListNode pB = headB;

        while (pA != pB) {
            pA = (pA == null) ? headB : pA.next;
            pB = (pB == null) ? headA : pB.next;

        }
        return pA;

    }

    /**
     * 假设传入 listA = [1,9,1,2,4], listB = [3,2,4]，我们来看看算法是如何执行的：
     *
     *
     *
     * 初始化： pA = 1 (listA的头节点) pB = 3 (listB的头节点)
     *
     * 第1次循环： pA != pB, 所以继续 pA = 9 (pA.next) pB = 2 (pB.next)
     *
     * 第2次循环： pA != pB, 继续 pA = 1 (pA.next) pB = 4 (pB.next)
     *
     * 第3次循环： pA != pB, 继续 pA = 2 (pA.next) pB = null (pB.next)
     *
     * 第4次循环： pA != pB, 继续 pA = 4 (pA.next) pB = 1 (headA, 因为pB为null)
     *
     * 第5次循环： pA != pB, 继续 pA = null (pA.next) pB = 9 (pB.next)
     *
     * 第6次循环： pA != pB, 继续 pA = 3 (headB, 因为pA为null) pB = 1 (pB.next)
     *
     * 第7次循环： pA != pB, 继续 pA = 2 (pA.next) pB = 2 (pB.next)
     *
     * 第8次循环： pA == pB (都指向值为2的节点), 循环结束
     *
     * 返回 pA (值为2的节点)
     *
     * 在这个例子中，算法找到了两个链表的交点，即值为2的节点。这是因为从这个节点开始，两个链表共享相同的后续节点 [2,4]。
     *
     * 这个算法的巧妙之处在于，通过让两个指针分别遍历两个链表，然后在到达末尾时切换到另一个链表的头部，它实际上抵消了两个链表长度的差异。当两个指针相遇时，它们要么在交点处相遇（如果存在交点），要么同时到达null（如果不存在交点）。
     *
     * 在这个特定的例子中，pA遍历了 [1,9,1,2,4,3,2]，而pB遍历了 [3,2,4,1,9,1,2]，它们在第二个值为2的节点处相遇，这就是两个链表的交点。
     *
     * listA = [1,9,1,2,4,3,2]
     * listB = [3,2,4,1,9,1,2]
     */
    public static void main(String[] args) {

        ListNode listNode2 = new ListNode(2);
        // 创建 listA: [1,9,1,2,4]
        ListNode headA = new ListNode(1);
        headA.next = new ListNode(9);
        headA.next.next = new ListNode(1);
        headA.next.next.next = listNode2;
        headA.next.next.next.next = new ListNode(4);

        // 创建 listB: [3,2,4]
        ListNode headB = new ListNode(3);
        headB.next = listNode2;
        headB.next.next = new ListNode(4);

        IntersectionNode intersectionNode = new IntersectionNode();

        ListNode result = intersectionNode.getIntersectionNode(headA, headB);

        System.out.println("result:"+result.val);


    }
}
