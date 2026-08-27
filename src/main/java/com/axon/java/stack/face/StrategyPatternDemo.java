package com.axon.java.stack.face;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <h2>面试题 9: 策略模式 —— 干掉层层嵌套的 if/else</h2>
 *
 * <pre>
 * 反例 (日常开发常见的写法), 优惠计算一堆嵌套条件:
 *
 *   public BigDecimal calc(Order order) {
 *       if (order.getActivityType() != null) {
 *           if ("FULL_REDUCTION".equals(order.getActivityType())) {
 *               if (order.getAmount() >= 100) { ... } else { ... }      // 3 层
 *           } else if ("SECKILL".equals(...)) {
 *               if (user.isNewUser()) { ... } else if (user.isVip()) {..} // 更深
 *           }
 *       } else if (user.isVip()) { ... }
 *   }
 *
 * 问题:
 *   1. 违背开闭原则 — 每新增一种优惠都要改这段老代码, 全量回归
 *   2. 条件嵌套深, 可读性差, 改一行容易碰坏别的分支
 *   3. 多人协作时都往一个方法里堆逻辑, 合并冲突高发
 *
 * 正解 (策略模式):
 *   把每一个分支抽成一个"自描述"的策略类 (能自己回答"我处理这种情况吗"),
 *   再由工厂/容器根据 support 匹配来路由, 分支判断变成了多态分发。
 *
 *   新增一种优惠 = 新增一个策略类 + 注册进工厂, 老代码零改动。
 * </pre>
 */
public class StrategyPatternDemo {

    // ==================== 订单上下文 ====================

    /**
     * 携带所有可能影响策略选择的因子
     */
    public static class OrderContext {

        private final BigDecimal amount;
        private final boolean newUser;
        private final int vipLevel;        // 0 = 非会员
        private final String activityType; // null 表示未参加活动

        public OrderContext(BigDecimal amount, boolean newUser, int vipLevel, String activityType) {
            this.amount = amount;
            this.newUser = newUser;
            this.vipLevel = vipLevel;
            this.activityType = activityType;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public boolean isNewUser() {
            return newUser;
        }

        public int getVipLevel() {
            return vipLevel;
        }

        public String getActivityType() {
            return activityType;
        }

        @Override
        public String toString() {
            return "Order{amount=" + amount + ", newUser=" + newUser
                    + ", vipLevel=" + vipLevel + ", activity=" + activityType + "}";
        }
    }

    // ==================== 策略接口 ====================

    /**
     * <h3>优惠策略接口</h3>
     *
     * <pre>
     * 关键点:
     *   - support(): 策略"自我描述"能否处理当前场景 —— 这是替代 if/else 的核心
     *   - order():   当多个策略同时命中时, 数值小的优先级高 (避免匹配顺序不可控)
     * </pre>
     */
    public interface DiscountStrategy {

        String name();

        boolean support(OrderContext ctx);

        BigDecimal calPrice(OrderContext ctx);

        default int order() {
            return Integer.MAX_VALUE;
        }
    }

    // ==================== 具体策略实现 ====================

    /**
     * 策略1: 新用户立减 (优先级最高)
     */
    public static class NewUserCutStrategy implements DiscountStrategy {

        @Override
        public String name() {
            return "新用户立减";
        }

        @Override
        public boolean support(OrderContext ctx) {
            return ctx.isNewUser();
        }

        @Override
        public BigDecimal calPrice(OrderContext ctx) {
            return ctx.getAmount().subtract(new BigDecimal("10")).max(BigDecimal.ZERO);
        }

        @Override
        public int order() {
            return 10;
        }
    }

    /**
     * 策略2: VIP 折扣
     */
    public static class VipDiscountStrategy implements DiscountStrategy {

        @Override
        public String name() {
            return "VIP折扣";
        }

        @Override
        public boolean support(OrderContext ctx) {
            return ctx.getVipLevel() >= 3;
        }

        @Override
        public BigDecimal calPrice(OrderContext ctx) {
            // 不同 vip 等级不同折扣, 复杂度被封装在策略类内部
            BigDecimal rate = ctx.getVipLevel() >= 5
                    ? new BigDecimal("0.80") : new BigDecimal("0.90");
            return ctx.getAmount().multiply(rate).setScale(2, BigDecimal.ROUND_HALF_UP);
        }

        @Override
        public int order() {
            return 20;
        }
    }

    /**
     * 策略3: 大促满减活动
     */
    public static class FullReductionStrategy implements DiscountStrategy {

        @Override
        public String name() {
            return "满100减20";
        }

        @Override
        public boolean support(OrderContext ctx) {
            return "FULL_REDUCTION".equals(ctx.getActivityType())
                    && ctx.getAmount().compareTo(new BigDecimal("100")) >= 0;
        }

        @Override
        public BigDecimal calPrice(OrderContext ctx) {
            return ctx.getAmount().subtract(new BigDecimal("20"));
        }

        @Override
        public int order() {
            return 30;
        }
    }

    /**
     * 策略4: 兜底策略 —— 谁都不匹配时原价购买
     * 保证路由调用永远有结果, 不需要在调用方写 null 判断
     */
    public static class OriginalPriceStrategy implements DiscountStrategy {

        @Override
        public String name() {
            return "无优惠原价";
        }

        @Override
        public boolean support(OrderContext ctx) {
            return true;
        }

        @Override
        public BigDecimal calPrice(OrderContext ctx) {
            return ctx.getAmount();
        }
    }

    // ==================== 策略工厂 (路由器) ====================

    /**
     * <h3>策略工厂: 用"自动匹配"取代 if/else 分发</h3>
     *
     * <pre>
     * 接入 Spring 时更简单, 连 register 都可以省掉:
     *
     *   {@code @Component}
     *   public class DiscountStrategyFactory {
     *       // Spring 会把容器里所有 DiscountStrategy 实现自动注入进来
     *       {@code @Autowired(required = false)}
     *       private List<DiscountStrategy> strategies;
     *   }
     *
     * 业务方只需给新策略打上 @Component 注解即可生效, 天然符合开闭原则。
     * </pre>
     */
    public static class DiscountStrategyFactory {

        private final List<DiscountStrategy> strategies = new ArrayList<>();
        private final DiscountStrategy fallback;

        public DiscountStrategyFactory(List<DiscountStrategy> strategies, DiscountStrategy fallback) {
            this.strategies.addAll(strategies);
            this.fallback = fallback;
            // 按 order 升序排好, 命中顺序从此确定、可控
            this.strategies.sort(Comparator.comparingInt(DiscountStrategy::order));
        }

        public BigDecimal calPrice(OrderContext ctx) {
            // 原来 N 层 if/else 的位置, 现在只有一次"过滤器式"匹配
            DiscountStrategy matched = strategies.stream()
                    .filter(s -> s.support(ctx))
                    .findFirst()
                    .orElse(fallback);
            System.out.println("命中策略 -> " + matched.name());
            return matched.calPrice(ctx);
        }
    }

    // ==================== 运行验证 ====================

    public static void main(String[] args) {
        DiscountStrategyFactory factory = new DiscountStrategyFactory(
                java.util.Arrays.asList(
                        new NewUserCutStrategy(),
                        new VipDiscountStrategy(),
                        new FullReductionStrategy()),
                new OriginalPriceStrategy());

        List<OrderContext> orders = java.util.Arrays.asList(
                new OrderContext(new BigDecimal("120"), true, 0, "FULL_REDUCTION"), // 命中新人立减 (优先级高)
                new OrderContext(new BigDecimal("120"), false, 5, "FULL_REDUCTION"),// 命中 VIP 折扣
                new OrderContext(new BigDecimal("120"), false, 1, "FULL_REDUCTION"),// 命中满减
                new OrderContext(new BigDecimal("50"), false, 0, null));            // 无命中原价

        for (OrderContext order : orders) {
            System.out.println(order);
            System.out.println("  应付金额: " + factory.calPrice(order));
        }
    }
}
