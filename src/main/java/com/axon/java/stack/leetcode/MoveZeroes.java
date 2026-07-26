package com.axon.java.stack.leetcode;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 *  零移动
 * @author：liupengfei
 * @date：2025/8/21
 * @description：
 */
public class MoveZeroes {

    public static void main(String[] args) {

      int []  nums =new int [] {0,1,0,3,12};
      MoveZeroes moveZeroes = new MoveZeroes();
      moveZeroes.moveZeroes2(nums);
      System.out.println("result:" + Arrays.toString(nums));
    }

    /**
     *  零移动
     * @param nums 数组
     */
    public void moveZeroes(int[] nums) {

        for (int i = 0; i < nums.length; i++) {

            for (int j = 0; j <nums.length-i-1; j++) {

                if(nums[j]==0){
                    int temp = nums[j+1];
                    nums[j+1]=nums[j];
                    nums[j]=temp;
                }
            }
        }
    }

    /**
     *  这是优化后的版本
     *
     *  {0,1,0,3,12}
     *  当然，我会为您详细列出优化后的 `moveZeroes` 方法处理输入数组 {0,1,0,3,12} 的执行步骤。我们将使用之前提供的优化版本：
     *
     * 非常抱歉我之前的解释有误。您说得对，我会重新详细解释这个过程。让我们再次看一下代码，然后逐步执行：
     *
     * ```java
     * public void moveZeroes(int[] nums) {
     *     int nonZeroIndex = 0;
     *
     *     for (int i = 0; i < nums.length; i++) {
     *         if (nums[i] != 0) {
     *             nums[nonZeroIndex++] = nums[i];
     *         }
     *     }
     *
     *     while (nonZeroIndex < nums.length) {
     *         nums[nonZeroIndex++] = 0;
     *     }
     * }
     * ```
     *
     * 现在，让我们正确地逐步执行这个方法，输入数组为 {0,1,0,3,12}：
     *
     * 1. 初始状态：
     *    - nums = {0,1,0,3,12}
     *    - nonZeroIndex = 0
     *
     * 2. 第一次循环 (i = 0):
     *    - nums[0] 是 0，不做任何操作
     *    - nums = {0,1,0,3,12}
     *    - nonZeroIndex = 0
     *
     * 3. 第二次循环 (i = 1):
     *    - nums[1] 是 1，非零
     *    - 将 1 移到 nonZeroIndex 位置（index 0）
     *    - nums = {1,1,0,3,12}
     *    - nonZeroIndex 增加到 1
     *
     * 4. 第三次循环 (i = 2):
     *    - nums[2] 是 0，不做任何操作
     *    - nums = {1,1,0,3,12}
     *    - nonZeroIndex = 1
     *
     * 5. 第四次循环 (i = 3):
     *    - nums[3] 是 3，非零
     *    - 将 3 移到 nonZeroIndex 位置（index 1）
     *    - nums = {1,3,0,3,12}
     *    - nonZeroIndex 增加到 2
     *
     * 6. 第五次循环 (i = 4):
     *    - nums[4] 是 12，非零
     *    - 将 12 移到 nonZeroIndex 位置（index 2）
     *    - nums = {1,3,12,3,12}
     *    - nonZeroIndex 增加到 3
     *
     * 7. 主循环结束，开始填充剩余位置为 0:
     *    - 将 nums[3] 设为 0
     *    - nums = {1,3,12,0,12}
     *    - nonZeroIndex 增加到 4
     *
     * 8. 继续填充:
     *    - 将 nums[4] 设为 0
     *    - nums = {1,3,12,0,0}
     *    - nonZeroIndex 增加到 5
     *
     * 9. 方法结束，最终结果:
     *    - nums = {1,3,12,0,0}
     *
     * 这次的解释更准确地反映了算法的执行过程。算法首先将所有非零元素移到数组的前面，保持它们的相对顺序，然后将剩余的位置填充为0。这个方法只遍历数组一次，时间复杂度为 O(n)，其中 n 是数组的长度。
     *
     * 再次为之前的错误向您道歉，感谢您的指出，这让我能够提供更准确的解释。
     * @param nums
     */
    public void moveZeroes2(int[] nums) {
        int nonZeroIndex = 0;

        // 将所有非零元素移到数组前面
        for (int i = 0; i < nums.length; i++) {
            if (nums[i] != 0) {
                nums[nonZeroIndex++] = nums[i];
            }
        }

        // 将剩余位置填充为0
        while (nonZeroIndex < nums.length) {
            nums[nonZeroIndex++] = 0;
        }
    }
}
