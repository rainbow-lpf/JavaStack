package com.axon.java.stack.seata;

/**
 * <h2>混合模式 — 一个全局事务里同时用 AT + TCC</h2>
 *
 * <pre>
 * 这是真实项目中最常见的写法。
 *
 * 你的项目里可能 90% 的操作都是纯 SQL → 用 AT
 * 只有那么 2-3 个操作调了第三方接口 → 用 TCC
 *
 * Seata 允许一个 @GlobalTransactional 下混用任意模式。
 * </pre>
 */
public class MixedModeDemo {

    /**
     * <h3>场景: 第四方支付平台，一笔支付完整流程</h3>
     *
     * <pre>
     * 涉及服务:
     *   orderService      → 订单 (纯 SQL)           → AT 模式
     *   alipayService     → 调支付宝扣款 (第三方接口)  → TCC 模式
     *   couponService     → 使用优惠券 (纯 SQL)      → AT 模式
     *   balanceService    → 冻结平台余额 (防并发)     → TCC 模式
     *   transactionLog    → 交易流水 (纯 INSERT)     → AT 模式
     *   riskControl       → 风控检查 (调风控接口)     → TCC 模式
     *
     * 5个微服务，3个用AT，2个用TCC，同一个全局事务。
     * </pre>
     */
    public static class PaymentService {

        // @Autowired private OrderService orderService;         // AT
        // @Autowired private AlipayTccService alipayTccService; // TCC
        // @Autowired private CouponService couponService;       // AT
        // @Autowired private BalanceTccService balanceTccService; // TCC
        // @Autowired private TransactionLogService logService;  // AT

        /**
         * <h3>完整支付流程</h3>
         *
         * <pre>
         * RollbackFor = Exception.class:  任何异常都回滚
         * timeoutMills = 60000:           整个全局事务超时 60 秒
         * </pre>
         */
        // @GlobalTransactional(name = "payment-process", timeoutMills = 60000, rollbackFor = Exception.class)
        public String processPayment(Long userId, String orderId, Long amount, Long couponId) {
            // ───── AT 模式 ─────
            // 1. 创建订单
            // orderService.create(userId, orderId, amount);
            // → INSERT INTO order → undo_log 记录 DELETE

            // ───── TCC 模式 ─────
            // 2. 调支付宝扣款 (Try)
            // alipayTccService.tryPay(orderId, amount);
            // → 调支付宝预下单，返回支付凭证

            // ───── AT 模式 ─────
            // 3. 使用优惠券
            // couponService.use(couponId);
            // → UPDATE coupon SET status='USED' → undo_log 记录反向 UPDATE

            // ───── TCC 模式 ─────
            // 4. 冻结平台手续费 (Try)
            // balanceTccService.tryFreeze(userId, fee);
            // → 用户余额: balance -= fee, frozen += fee

            // ───── AT 模式 ─────
            // 5. 记交易流水
            // logService.record(userId, orderId, amount, couponId);
            // → INSERT INTO transaction_log → undo_log 记录 DELETE

            // ───── TCC 模式 ─────
            // 6. 风控检查 (Try)
            // riskControlService.tryCheck(userId, amount);
            // → 调风控系统接口

            // ==========================================
            // Try 阶段全部成功:
            //   TC 通知所有 TCC RM: 执行 Confirm
            //   AT 的 undo_log 全部清除
            //   → 订单创建成功，钱扣了，券用了
            //
            // 假设第4步(冻结手续费) Try 失败:
            //   TC 通知所有 RM: 回滚
            //   支付宝 Confirm/Cancel → Cancel (不退钱，因为还没真扣)
            //   余额 Confirm/Cancel → Cancel (解冻)
            //   风控 Confirm/Cancel → Cancel
            //   AT 反向: DELETE order / UPDATE coupon 回恢复
            //   → 所有数据恢复初始状态
            // ==========================================

            return "success";
        }
    }

    /**
     * <h3>AT 和 TCC 在一行代码上怎么区分</h3>
     *
     * <pre>
     * 你写代码时完全不需要关心谁是什么模式:
     *
     *   orderService.create();     // 你当普通方法调
     *   alipayTccService.tryPay(); // 你当普通方法调
     *
     * 区别在哪？在服务提供方的定义:
     *
     *   OrderService.create() 的 DAO 数据源被 Seata 代理 → 自动 AT
     *   AlipayTccService.tryPay() 加了 @TwoPhaseBusinessAction → 走 TCC
     *
     * 调用方不需要知道、也不应该关心下游用的是什么模式。
     * </pre>
     */
    public static void callerPerspective() {
    }
}
