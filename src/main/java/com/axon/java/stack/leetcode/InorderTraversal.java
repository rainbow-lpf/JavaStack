package com.axon.java.stack.leetcode;

import javax.swing.tree.TreeNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *  二叉树的中序遍历
 * @author：liupengfei
 * @date：2025/8/22
 * @description：
 */
public class InorderTraversal {

    static class TreeNode {
        int val;

        TreeNode  left;
        TreeNode right;

        TreeNode() {
        }

        TreeNode(int val) {
            this.val = val;
        }

        TreeNode(int val, TreeNode left, TreeNode right) {
            this.val = val;
            this.left = left;
            this.right = right;
        }
    }



    public static void main(String[] args) {

        TreeNode root = new TreeNode(1);
        TreeNode treeNodeRight = new TreeNode(2);
        treeNodeRight.right=new TreeNode(3);
        root.right=treeNodeRight;
        InorderTraversal inorderTraversal = new InorderTraversal();
        List<Integer> result = inorderTraversal.inorderTraversal(root);
        System.out.println(Arrays.toString(new List[]{ result }));
    }


    List<Integer> result = new ArrayList<>();

    /**
     * 中序遍历
     * @param root 根节点
     * @return 返回中序遍历的结果
     */
    public List<Integer> inorderTraversal(TreeNode root) {
        if (root == null) {
            return null;
        }
        if (root.left !=null) {
            inorderTraversal(root.left);
        }

        result.add(root.val);

        if (root.right !=null) {
            inorderTraversal(root.right);
        }

        return result;
    }




}


