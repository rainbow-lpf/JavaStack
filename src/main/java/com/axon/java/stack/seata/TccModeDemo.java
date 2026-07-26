package com.axon.java.stack.seata;

/**
 * <h2>TCC 模式 — SpringBoot 完整配置 + 代码示例</h2>
 *
 * <h3>核心：你需要写三个方法 (Try / Confirm / Cancel)</h3>
 *
 * <h3>第 1 步: pom.xml 同 AT 模式</h3>
 * <h3>第 2 步: application.yml 同 AT 模式，data-source-proxy-mode 仍用 AT</h3>
 * <h3>第 3 步: 业务代码里声明 TCC 接口</h3>
 */
public class TccModeDemo {

    /**
     * <h3>TCC 接口定义</h3>
     *
     * <pre>
     * 关键注解: @TwoPhaseBusinessAction
     *   - name: 全局唯一名称
     *   - commitMethod: Confirm 方法名
     *   - rollbackMethod: Cancel 方法名
     *   - useTCCFence: 是否使用 TCC 防悬挂 (推荐 true, 需要建 tcc_fence_log 表)
     * </pre>
     */
    public interface DeductAccountTcc {

        /**
         * Try 阶段: 冻结金额，不真扣
         *
         * @param businessActionContext Seata 传递的上下文，Try 和 Confirm/Cancel 之间传参
         * @param userId               用户ID
         * @param amount               金额
         * @return 是否成功
         */
        // @TwoPhaseBusinessAction(
        //     name = "deductAccountTcc",
        //     commitMethod = "confirm",
        //     rollbackMethod = "cancel",
        //     useTCCFence = true
        // )
        boolean tryDeduct(
                // BusinessActionContext businessActionContext,
                //                              ↑ 这里解注释会编译报错，实际在Spring容器中由Seata代理
                Long userId,
                Long amount);
    }

    /**
     * <h3>TCC 实现类 — 扣余额</h3>
     *
     * <pre>
     * 核心思路:
     *   账户表设计: balance (总余额) + frozen (冻结金额)
     *
     *   Try:     balance -= amount,  frozen += amount   (钱还在，只是冻住了)
     *   Confirm: frozen -= amount                        (真正扣掉)
     *   Cancel:  frozen -= amount,  balance += amount   (解冻，恢复余额)
     *
     *   注意每个 SQL 都带 WHERE 条件做幂等保护
     * </pre>
     */
    public static class DeductAccountTccImpl {  // implements DeductAccountTcc

        // @Autowired private AccountDao accountDao;

        /**
         * Try: 冻结金额
         *
         * 做什么: 余额 → 冻结
         * SQL:   UPDATE account
         *        SET balance = balance - amount, frozen = frozen + amount
         *        WHERE user_id = ? AND balance >= amount
         *
         *        ↑ balance >= amount 是幂等保护: 余额不够就别冻
         */
        public boolean tryDeduct(Long userId, Long amount) {
            // int rows = accountDao.tryFreeze(userId, amount);

            // 把 try 的结果传给 Confirm/Cancel 用
            // businessActionContext.addContext("userId", userId);
            // businessActionContext.addContext("amount", amount);

            return true;
        }

        /**
         * Confirm: 真正扣钱
         *
         * 做什么: 冻结 → 消失
         * SQL:   UPDATE account SET frozen = frozen - amount
         *        WHERE user_id = ? AND frozen >= amount
         *
         *        ↑ frozen >= amount 是幂等保护
         */
        public boolean confirm(Long userId, Long amount) {
            // return accountDao.confirmDeduct(userId, amount) > 0;
            return true;
        }

        /**
         * Cancel: 解冻
         *
         * 做什么: 冻结 → 余额
         * SQL:   UPDATE account
         *        SET frozen = frozen - amount, balance = balance + amount
         *        WHERE user_id = ? AND frozen >= amount
         *
         *        处理空回滚: 如果 frozen < amount → Try 没执行过 → return true
         */
        public boolean cancel(Long userId, Long amount) {
            // return accountDao.cancelFreeze(userId, amount) > 0;
            return true;
        }
    }

    /**
     * <h3>TCC 优惠券服务</h3>
     *
     * <pre>
     * 状态设计: UNUSED → FROZEN → USED / UNUSED
     *
     * Try:     STATUS = FROZEN WHERE STATUS = UNUSED
     * Confirm: STATUS = USED   WHERE STATUS = FROZEN
     * Cancel:  STATUS = UNUSED WHERE STATUS = FROZEN
     *
     * 每个 WHERE 条件 = 幂等 + 状态校验
     * </pre>
     */
    public static class CouponServiceTcc {

        public boolean tryUseCoupon(Long couponId) {
            // UPDATE coupon SET status = 'FROZEN'
            // WHERE id = ? AND status = 'UNUSED'
            return true;
        }

        public boolean confirmUseCoupon(Long couponId) {
            // UPDATE coupon SET status = 'USED'
            // WHERE id = ? AND status = 'FROZEN'
            return true;
        }

        public boolean cancelUseCoupon(Long couponId) {
            // UPDATE coupon SET status = 'UNUSED'
            // WHERE id = ? AND status = 'FROZEN'
            return true;
        }
    }

    /**
     * <h3>调用方 — 全局事务混用 AT 和 TCC</h3>
     *
     * <pre>
     * @GlobalTransactional
     * public void placeOrder(OrderRequest req) {
     *     // AT 模式 — 纯 SQL
     *     orderService.create(req);         // INSERT → 回滚时 DELETE
     *
     *     // TCC 模式 — 调第三方支付
     *     alipayTccService.tryPay(req);     // 自己写的 Try/Confirm/Cancel
     *
     *     // TCC 模式 — 冻结优惠券
     *     couponService.tryUseCoupon(req.couponId);  // 自己写的 Try/Confirm/Cancel
     *
     *     // AT 模式 — 记日志
     *     logService.record(req);           // INSERT → 回滚时 DELETE
     * }
     *
     * Try 阶段全部成功 → Seata 调所有 TCC 的 Confirm
     * 任意一步失败      → Seata 调所有 TCC 的 Cancel + AT 的 undo_log 反向 SQL
     * </pre>
     */
    public static class OrderController {
        // @Autowired private OrderService orderService;
        // @Autowired private AlipayTccService alipayTccService;
        // @Autowired private CouponService couponService;

        // @GlobalTransactional(timeoutMills = 60000, name = "place-order", rollbackFor = Exception.class)
        public String placeOrder() {
            return "success";
        }
    }

    /**
     * <h3>TCC 防悬挂: 需要 tcc_fence_log 表</h3>
     *
     * <pre>
     * CREATE TABLE `tcc_fence_log` (
     *   `xid` varchar(128) NOT NULL,
     *   `branch_id` bigint NOT NULL,
     *   `action_name` varchar(64) NOT NULL,
     *   `status` tinyint NOT NULL,
     *   `gmt_create` datetime(3) NOT NULL,
     *   `gmt_modified` datetime(3) NOT NULL,
     *   PRIMARY KEY (`xid`, `branch_id`)
     * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
     *
     * 注解加 useTCCFence = true 后，Seata 自动利用这张表:
     *   Try 到达 → 插入记录 → Cancel 先到了 → 也插记录 → Try 慢悠悠到了
     *   → 发现有 Cancel 记录 → Try 被拒绝 → 防止业务悬挂
     * </pre>
     */
    public static void tccFenceLogTable() {
    }
}
