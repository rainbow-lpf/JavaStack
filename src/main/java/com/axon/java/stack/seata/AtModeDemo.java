package com.axon.java.stack.seata;

/**
 * <h2>AT 模式 — 最常用，SpringBoot 配置 + 代码完整示例</h2>
 *
 * <h3>第 1 步: pom.xml 加依赖</h3>
 * <pre>{@code
 * <!-- Seata Spring Boot Starter -->
 * <dependency>
 *     <groupId>io.seata</groupId>
 *     <artifactId>seata-spring-boot-starter</artifactId>
 *     <version>1.6.1</version>
 * </dependency>
 *
 * <!-- 注册中心 (Nacos) -->
 * <dependency>
 *     <groupId>com.alibaba.nacos</groupId>
 *     <artifactId>nacos-client</artifactId>
 *     <version>2.2.0</version>
 * </dependency>
 * }</pre>
 *
 * <h3>第 2 步: application.yml</h3>
 * <pre>{@code
 * seata:
 *   enabled: true
 *   application-id: ${spring.application.name}
 *   tx-service-group: default_tx_group      # 事务分组，对应 Seata Server 配置
 *   data-source-proxy-mode: AT               # 数据源代理模式
 *   registry:
 *     type: nacos
 *     nacos:
 *       application: seata-server
 *       server-addr: localhost:8848
 *       group: SEATA_GROUP
 *   config:
 *     type: nacos
 *     nacos:
 *       server-addr: localhost:8848
 *       group: SEATA_GROUP
 * }</pre>
 *
 * <h3>第 3 步: 每个业务数据库建 undo_log 表</h3>
 * <pre>{@code
 * CREATE TABLE `undo_log` (
 *   `id` bigint(20) NOT NULL AUTO_INCREMENT,
 *   `branch_id` bigint(20) NOT NULL,
 *   `xid` varchar(100) NOT NULL,
 *   `context` varchar(128) NOT NULL,
 *   `rollback_info` longblob NOT NULL,
 *   `log_status` int(11) NOT NULL,
 *   `log_created` datetime NOT NULL,
 *   `log_modified` datetime NOT NULL,
 *   PRIMARY KEY (`id`),
 *   UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8;
 * }</pre>
 *
 * <h3>第 4 步: 业务代码直接加注解即可</h3>
 */
public class AtModeDemo {

    /**
     * <h3>AT 模式业务代码 — 下单同时扣库存、扣余额</h3>
     *
     * <pre>
     * 你可能以为 AT 模式需要写很多代码？不用。
     * 就加一个 @GlobalTransactional，其他跟平时写 @Transactional 一样。
     *
     * 框架自动做的事:
     *   一阶段: 执行你的SQL → 提交 → 记 undo_log(反向SQL)
     *   二阶段成功: 删 undo_log
     *   二阶段失败: 执行 undo_log 里的反向SQL → 删 undo_log
     * </pre>
     */
    public static class OrderService {

        // 注意: 这是伪代码演示，实际依赖 Spring 注入
        // @Autowired private OrderDao orderDao;
        // @Autowired private AccountDao accountDao;
        // @Autowired private StockDao stockDao;

        /**
         * 只需一个注解，AT 模式自动生效
         *
         * timeoutMills: 全局事务超时时间
         * rollbackFor:  哪些异常触发回滚
         */
        // @GlobalTransactional(timeoutMills = 300000, name = "create-order", rollbackFor = Exception.class)
        public void createOrder(Long userId, Long productId, Integer count) {
            // 这三个 SQL 分别在三个微服务、三个数据库
            // 但 Seata 把它们纳入同一个全局事务

            // 1. 创建订单 → 数据库A
            // orderDao.insert(order);
            // → AT: INSERT 的反向是 DELETE

            // 2. 扣账户余额 → 数据库B
            // accountDao.deduct(userId, totalAmount);
            // → AT: UPDATE amount-100 的反向是 UPDATE amount+100

            // 3. 减库存 → 数据库C
            // stockDao.reduce(productId, count);
            // → AT: UPDATE stock-1 的反向是 UPDATE stock+1

            // 如果第3步抛异常:
            // → TC 通知数据库A和B执行 undo_log 里的反向SQL
            // → DELETE order / UPDATE amount+100
            // → 三个库全部回到最初状态
        }
    }

    /**
     * <h3>AT 模式脏写问题 & 全局锁</h3>
     *
     * <pre>
     * 问题场景:
     *   事务A: UPDATE stock=10 (一阶段提交，本地行锁释放)
     *   事务B: UPDATE stock=9  (在A的基础上减，提交)
     *   事务A: 二阶段回滚，反向SQL UPDATE stock=11
     *   结果: stock=11，B的事务被覆盖了！
     *
     * Seata 解决方案——全局锁:
     *   事务A 一阶段提交前 → 向 TC 申请全局锁 (表名 + 主键)
     *   事务B 想更新同一行 → 去 TC 查全局锁 → 发现被A锁了
     *     → 等待 (默认10秒) 或 快速失败
     *   事务A 二阶段完成 → 释放全局锁 → 事务B 获取锁继续
     *
     * 注意: 全局锁只在 TC 内存中存在，不依赖数据库。
     *       如果 TC 挂了，全局锁就没了。
     * </pre>
     */
    public static void globalLockExplanation() {
        // 全局锁 在你的代码完全无感知，不需要写任何代码
        // Seata 的 DataSourceProxy 自动在 UPDATE 前向 TC 申请锁
    }

    /**
     * <h3>AT 模式的局限</h3>
     *
     * <pre>
     * 下面这些操作，AT 管不了:
     *
     * ❌ 调支付宝接口扣款     → 没有"回滚支付宝"这种SQL
     * ❌ 发短信/邮件         → 发出去没法撤回
     * ❌ Redis 缓存操作      → Redis 没有 undo_log
     * ❌ MongoDB 写入        → 文档DB不支持反向SQL(除非你配Seata MongoDB支持)
     *
     * ✅ 只有关系型数据库的 INSERT/UPDATE/DELETE → AT 才能自动回滚
     * </pre>
     */
    public static void atModeLimitations() {
    }
}
