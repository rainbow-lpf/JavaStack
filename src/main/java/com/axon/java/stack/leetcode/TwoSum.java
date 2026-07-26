package com.axon.java.stack.leetcode;

import java.util.HashMap;
import java.util.Map;

/**
 *
 *  两数之和
 * @author：liupengfei
 * @date：2025/8/20
 * @description：
 */
public class TwoSum {


    public int[] twoSum(int[] nums, int target) {

        Map<Integer, Integer> resultMap=new HashMap<>();
        for(int i=0;i<nums.length ;i++){
            int result=target-nums[i];
            if(resultMap.containsKey(result)){
                return new int[] {resultMap.get(result),i};
            }
            resultMap.put(nums[i],i);

        }
        return new int[]{};

    }

    public static void main(String[] args) {

        int [] nums = new int[]{1,2,3,9,7,6,10 ,11, 12};

        int target = 10;

        TwoSum twoSum = new TwoSum();
        int[] result = twoSum.twoSum(nums, target);

        System.out.println("result:"+result[0]+","+result[1]);

    }
}
