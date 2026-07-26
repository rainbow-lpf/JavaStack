package com.axon.java.stack.seata;

/**
 * <h2>XA 模式 — 配置 + 使用</h2>
 *
 * <h3>第 1 步: application.yml 配置</h3>
 * <pre>{@code
 * seata:
 *   data-source-proxy-mode: XA          # 关键: 把 AT 改成 XA
 *   enabled: true
 *   tx-service-group: default_tx_group
 *   registry:
 *     type: nacos
 *     nacos:
 *       server-addr: localhost:8848
 *   config:
 *     type: nacos
 *     nacos:
 *       server-addr: localhost:8848
 * }</pre>
 *
 * <h3>第 2 步: 数据库确认支持 XA</h3>
 * <pre>{@code
 * -- MySQL 5.7+ InnoDB 默认支持 XA
 * -- 确认:
 * SHOW ENGINES;
 * -- InnoDB 行有 XA 字样 = 支持
 * }</pre>
 *
 * <h3>第 3 步: 业务代码完全不用改</h3>
 *
 * <h3>XA 和 AT 的代码区别</h3>
 * <pre>
 * AT 模式: @GlobalTransactional + data-source-proxy-mode: AT
 * XA 模式: @GlobalTransactional + data-source-proxy-mode: XA
 *         ↑ 就改一行配置，代码一模一样 ↑
 * </pre>
 */
public class XaModeDemo {

    /**
     * <h3>XA 模式完整代码</h3>
     *
     * <pre>
     * @GlobalTransactional  // 一样的注解
     * @Transactional         // 本地事务
     * public void transfer(Long fromId, Long toId, BigDecimal amount) {
     *     // 减钱 → 数据库A
     *     accountDao.deduct(fromId, amount);
     *
     *     // 加钱 → 数据库B
     *     accountDao.addBalance(toId, amount);
     *
     *     // AT 和 XA 的区别 只 在 于:
     *     //
     *     // AT: 这两句 SQL 直接提交，另记 undo_log，失败了反向 SQL
     *     // XA: 这两句 SQL 执行但 不提交，数据库行锁一直持有
     *     //      等 TC 通知 → 全部 OK 就 commit → 有失败就 rollback
     *     //
     *     // 对 你 写 代 码 完 全 无 感 知
     * }
     * </pre>
     */
    public static class TransferService {
    }

    /**
     * <h3>XA vs AT 锁持有时间对比（重要！）</h3>
     *
     * <pre>
     * XA 转账流程:
     *   T0: 一阶段 — 执行 SQL，数据库锁住 from 和 to 两行
     *   T1: 等 TC 全局决策（网络延迟 50ms）
     *   T2: 等注册中心通知（网络延迟 50ms）
     *   T3: 二阶段 — 提交，释放锁
     *   总持锁时间: 100ms+
     *
     * AT 转账流程:
     *   T0: 一阶段 — 执行 SQL → 提交（释放数据库锁！）
     *       → 向 TC 申请全局锁（在 TC 内存中，不影响数据库）
     *   T1: 等 TC 全局   T2: 二阶段 — OK 删 undo_log / 失败 反向 SQL
     *   数据库持锁时间: 几乎为 0
     *
     * 结论:
     *   高并发场景用 AT（锁短）
     *   钱相关强一致用 XA（防一切异常，包括 TC 宕机导致全局锁丢失）
     * </pre>
     */
    public static void lockComparison() {
    }
}
