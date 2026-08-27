package com.axon.java.stack.face.simple;

import java.util.HashMap;
import java.util.Map;

/**
 *  策略模式
 * 以前算优惠：根据用户类型写一串 if else，每加一种优惠就得改老代码，嵌套也越来越深
 * 现在每种优惠一个类，扔进 map 里按 key 取，新加优惠只要新增一个类就行
 */
public class DiscountStrategy {

    /** 策略接口 */
    interface Strategy {
        String name();

        double calc(double price);
    }

    /** 新人打8折 */
    static class NewUserStrategy implements Strategy {
        public String name() {
            return "新人折扣";
        }

        public double calc(double price) {
            return price * 0.8;
        }
    }

    /** vip打9折 */
    static class VipStrategy implements Strategy {
        public String name() {
            return "vip折扣";
        }

        public double calc(double price) {
            return price * 0.9;
        }
    }

    /** 满100减20 */
    static class FullReductionStrategy implements Strategy {
        public String name() {
            return "满100减20";
        }

        public double calc(double price) {
            return price >= 100 ? price - 20 : price;
        }
    }

    // key就相当于原来if else里的判断条件，用map查找代替if else
    static Map<String, Strategy> strategyMap = new HashMap<String, Strategy>();

    static {
        strategyMap.put("newUser", new NewUserStrategy());
        strategyMap.put("vip", new VipStrategy());
        strategyMap.put("full", new FullReductionStrategy());
    }

    static double calcPrice(String type, double price) {
        Strategy s = strategyMap.get(type);
        if (s == null) {   // 没匹配上就走原价兜底
            return price;
        }
        System.out.println("命中策略：" + s.name());
        return s.calc(price);
    }

    public static void main(String[] args) {
        System.out.println(calcPrice("newUser", 120));
        System.out.println(calcPrice("vip", 120));
        System.out.println(calcPrice("full", 120));

        // 随便传个不存在的类型，走原价
        System.out.println(calcPrice("abc", 120));
    }
}
