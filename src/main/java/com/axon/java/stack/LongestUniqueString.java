package com.axon.java.stack;

import java.util.*;

/**
 * "最长不重复字符串"三道面试变体
 */
public class LongestUniqueString {

    /** ==================== 题1 ====================
     * 最长无重复子串：返回长度
     * 例：abcabcbb → "abc" → 3
     */
    public static int lengthOfLongestSubstring(String s) {
        if (s == null || s.isEmpty()) return 0;
        Set<Character> set = new HashSet<>();
        int left = 0, max = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            while (set.contains(c)) {
                set.remove(s.charAt(left++));
            }
            set.add(c);
            max = Math.max(max, right - left + 1);
        }
        return max;
    }

    /** ==================== 题1 扩展 ====================
     * 最长无重复子串：返回子串本身
     */
    public static String longestSubstring(String s) {
        if (s == null || s.isEmpty()) return "";
        Set<Character> set = new HashSet<>();
        int left = 0, maxLen = 0, start = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            while (set.contains(c)) {
                set.remove(s.charAt(left++));
            }
            set.add(c);
            if (right - left + 1 > maxLen) {
                maxLen = right - left + 1;
                start = left;
            }
        }
        return s.substring(start, start + maxLen);
    }

    /** ==================== 题2 ====================
     * 去重：保留原字符串中不重复的字符，按原顺序
     * 例：abacbdeff → "abcdef"
     */
    public static String deduplicate(String s) {
        if (s == null || s.isEmpty()) return "";
        Set<Character> set = new LinkedHashSet<>();
        for (char c : s.toCharArray()) {
            set.add(c);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : set) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** ==================== 题3 ====================
     * 随机字符串：生成长度指定、不含重复字符的随机字符串
     * 例：generateUniqueString(5) → "a3X9k"
     */
    private static final String ALL_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Random RANDOM = new Random();

    public static String generateUniqueString(int length) {
        if (length > ALL_CHARS.length()) {
            throw new IllegalArgumentException("长度不能超过可用字符数 " + ALL_CHARS.length());
        }
        // Fisher-Yates 洗牌：打乱 ALL_CHARS，然后取前 length 个
        char[] chars = ALL_CHARS.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars, 0, length);
    }

    // ==================== 测试 ====================
    public static void main(String[] args) {
        System.out.println("==== 题1：最长无重复子串（长度） ====");
        System.out.println(lengthOfLongestSubstring("abcabcbb"));  // 3
        System.out.println(lengthOfLongestSubstring("bbbbb"));     // 1
        System.out.println(lengthOfLongestSubstring("pwwkew"));    // 3

        System.out.println("\n==== 题1 扩展：返回子串 ====");
        System.out.println(longestSubstring("abcabcbb"));  // "abc"
        System.out.println(longestSubstring("pwwkew"));    // "wke"

        System.out.println("\n==== 题2：去重 ====");
        System.out.println(deduplicate("abacbdeff"));      // "abcdef"
        System.out.println(deduplicate("hello"));           // "helo"

        System.out.println("\n==== 题3：随机无重复字符串 ====");
        System.out.println(generateUniqueString(5));   // 如 "a3X9k"
        System.out.println(generateUniqueString(10));  // 如 "B7mNpQrSxY"
    }
}
