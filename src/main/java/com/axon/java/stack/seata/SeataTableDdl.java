package com.axon.java.stack.seata;

/**
 * <h2>Seata 建表 SQL 大全</h2>
 *
 * <pre>
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  表分为两类:                                                 ║
 * ║  1. Seata Server 库 (TC 用) — 3 张表                        ║
 * ║  2. 每个业务库 (RM 用)    — undo_log(AT) + tcc_fence_log(TCC)║
 * ╚══════════════════════════════════════════════════════════════╝
 * </pre>
 */
public class SeataTableDdl {

    // ============================================================
    // 第一部分: Seata Server 数据库 (seata)
    // ============================================================

    /**
     * <h3>1. global_table — 全局事务表</h3>
     *
     * <pre>
     * 作用: 记录每个全局事务的状态
     * 每次 @GlobalTransactional 方法执行时插入一条
     *
     * status:
     *   1 = Begin (开始)
     *   2 = Committing (提交中)
     *   3 = Committed (已提交)
     *   4 = Rollbacking (回滚中)
     *   5 = Rollbacked (已回滚)
     *   6 = TimeoutRollbacking (超时回滚中)
     *   7 = TimeoutRollbacked (超时已回滚)
     *
     * -- 建在 seata 库 --
     * </pre>
     */
    String createGlobalTable = "CREATE TABLE IF NOT EXISTS `global_table` (\n" +
            "  `xid`                       VARCHAR(128)  NOT NULL COMMENT '全局事务ID',\n" +
            "  `transaction_id`            BIGINT(20)    NOT NULL COMMENT '事务ID',\n" +
            "  `status`                    TINYINT(4)    NOT NULL COMMENT '状态: 1开始 2提交中 3已提交 4回滚中 5已回滚',\n" +
            "  `application_id`            VARCHAR(32)   NOT NULL COMMENT '应用名',\n" +
            "  `transaction_service_group` VARCHAR(32)   NOT NULL COMMENT '事务分组',\n" +
            "  `transaction_name`          VARCHAR(128)  DEFAULT NULL COMMENT '全局事务名称(@GlobalTransactional的name)',\n" +
            "  `timeout`                   INT(11)       DEFAULT NULL COMMENT '超时时间(毫秒)',\n" +
            "  `begin_time`                BIGINT(20)    DEFAULT NULL COMMENT '开始时间',\n" +
            "  `application_data`          VARCHAR(2000) DEFAULT NULL COMMENT '附加数据',\n" +
            "  `gmt_create`                DATETIME      DEFAULT NULL COMMENT '创建时间',\n" +
            "  `gmt_modified`              DATETIME      DEFAULT NULL COMMENT '修改时间',\n" +
            "  PRIMARY KEY (`xid`)\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局事务表';";

    /**
     * <h3>2. branch_table — 分支事务表</h3>
     *
     * <pre>
     * 作用: 记录每个 RM 的分支事务状态
     * 每个微服务被调用时插入一条(AT/TCC/XA/Saga)
     *
     * branch_type: AT / TCC / XA / SAGA
     * </pre>
     */
    String createBranchTable = "CREATE TABLE IF NOT EXISTS `branch_table` (\n" +
            "  `branch_id`         BIGINT(20)    NOT NULL COMMENT '分支事务ID',\n" +
            "  `xid`               VARCHAR(128)  NOT NULL COMMENT '所属全局事务ID',\n" +
            "  `transaction_id`    BIGINT(20)    NOT NULL COMMENT '事务ID',\n" +
            "  `resource_group_id` VARCHAR(32)   DEFAULT NULL COMMENT '资源组ID',\n" +
            "  `resource_id`       VARCHAR(256)  DEFAULT NULL COMMENT '资源ID(JDBC URL)',\n" +
            "  `branch_type`       VARCHAR(8)    DEFAULT NULL COMMENT '分支类型: AT/TCC/XA/SAGA',\n" +
            "  `status`            TINYINT(4)    DEFAULT NULL COMMENT '状态',\n" +
            "  `client_id`         VARCHAR(64)   DEFAULT NULL COMMENT '客户端ID',\n" +
            "  `application_data`  VARCHAR(2000) DEFAULT NULL COMMENT '附加数据',\n" +
            "  `gmt_create`        DATETIME(6)   DEFAULT NULL COMMENT '创建时间',\n" +
            "  `gmt_modified`      DATETIME(6)   DEFAULT NULL COMMENT '修改时间',\n" +
            "  PRIMARY KEY (`branch_id`),\n" +
            "  KEY `idx_xid` (`xid`)\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分支事务表';";

    /**
     * <h3>3. lock_table — 全局锁表（AT 模式专用）</h3>
     *
     * <pre>
     * 作用: 防止 AT 模式脏写
     * 一阶段提交前申请全局锁，写入此表
     * 其他事务更新同一行数据前检查此表，有锁则等待
     * </pre>
     */
    String createLockTable = "CREATE TABLE IF NOT EXISTS `lock_table` (\n" +
            "  `row_key`        VARCHAR(128)  NOT NULL COMMENT '行键 = resourceId + tableName + pk',\n" +
            "  `xid`            VARCHAR(128)  NOT NULL COMMENT '所属全局事务ID',\n" +
            "  `transaction_id` BIGINT(20)    NOT NULL COMMENT '事务ID',\n" +
            "  `branch_id`      BIGINT(20)    NOT NULL COMMENT '分支事务ID',\n" +
            "  `resource_id`    VARCHAR(256)  DEFAULT NULL COMMENT '数据库标识',\n" +
            "  `table_name`     VARCHAR(32)   DEFAULT NULL COMMENT '表名',\n" +
            "  `pk`             VARCHAR(36)   DEFAULT NULL COMMENT '主键值',\n" +
            "  `status`         TINYINT(4)    NOT NULL DEFAULT '0' COMMENT '状态',\n" +
            "  `gmt_create`     DATETIME      DEFAULT NULL COMMENT '创建时间',\n" +
            "  `gmt_modified`   DATETIME      DEFAULT NULL COMMENT '修改时间',\n" +
            "  PRIMARY KEY (`row_key`),\n" +
            "  KEY `idx_branch_id` (`branch_id`),\n" +
            "  KEY `idx_xid` (`xid`)\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局锁表';";

    // ============================================================
    // 第二部分: 每个业务数据库
    // ============================================================

    /**
     * <h3>4. undo_log — AT 模式回滚日志（每个业务库都要建）</h3>
     *
     * <pre>
     * 作用: 记录反向 SQL，二阶段回滚时执行
     * 建在: 每个微服务的业务数据库（订单库、账户库、库存库...各建一张）
     *
     * 数据样例:
     *   xid = "192.168.1.1:8091:123456"
     *   branch_id = 123456789
     *   rollback_info = {"sql":"UPDATE account SET balance = balance + 100 WHERE id = 1"}
     * </pre>
     */
    String createUndoLog = "CREATE TABLE IF NOT EXISTS `undo_log` (\n" +
            "  `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '自增主键',\n" +
            "  `branch_id`     BIGINT(20)   NOT NULL COMMENT '分支事务ID',\n" +
            "  `xid`           VARCHAR(100) NOT NULL COMMENT '全局事务ID',\n" +
            "  `context`       VARCHAR(128) NOT NULL COMMENT '上下文(序列化)',\n" +
            "  `rollback_info` LONGBLOB     NOT NULL COMMENT '回滚信息(反向SQL JSON)',\n" +
            "  `log_status`    INT(11)      NOT NULL COMMENT '状态: 0正常 1已全局提交',\n" +
            "  `log_created`   DATETIME     NOT NULL COMMENT '创建时间',\n" +
            "  `log_modified`  DATETIME     NOT NULL COMMENT '修改时间',\n" +
            "  PRIMARY KEY (`id`),\n" +
            "  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT模式回滚日志表';";

    /**
     * <h3>5. tcc_fence_log — TCC 防悬挂日志（每个用 TCC 的业务库建）</h3>
     *
     * <pre>
     * 作用: 用数据库唯一键防止 TCC 的空回滚和业务悬挂
     * 使用条件: @TwoPhaseBusinessAction(useTCCFence = true)
     *
     * 原理:
     *   Try 到达 → INSERT (xid, branch_id, status=尝试中)
     *   Cancel 先到 → INSERT (xid, branch_id, status=已回滚)
     *   Try 慢悠悠到 → INSERT → 主键冲突(xid+branch_id已存在) → Try 被拒绝
     *   → 防止业务悬挂
     * </pre>
     */
    String createTccFenceLog = "CREATE TABLE IF NOT EXISTS `tcc_fence_log` (\n" +
            "  `xid`           VARCHAR(128)  NOT NULL COMMENT '全局事务ID',\n" +
            "  `branch_id`     BIGINT(20)    NOT NULL COMMENT '分支事务ID',\n" +
            "  `action_name`   VARCHAR(64)   NOT NULL COMMENT 'TCC 方法名',\n" +
            "  `status`        TINYINT(4)    NOT NULL COMMENT '状态: 1尝试中 2已确认 3已回滚',\n" +
            "  `gmt_create`    DATETIME(3)   NOT NULL COMMENT '创建时间',\n" +
            "  `gmt_modified`  DATETIME(3)   NOT NULL COMMENT '修改时间',\n" +
            "  PRIMARY KEY (`xid`, `branch_id`)\n" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TCC防悬挂日志表';";

    // ============================================================
    // 第三部分: 表位置汇总
    // ============================================================

    /**
     * <h3>表分布汇总</h3>
     *
     * <pre>
     * ┌─────────────────────────────────────────────────────────┐
     * │ Seata Server 数据库 (seata)                              │
     * │   ├── global_table    全局事务状态                        │
     * │   ├── branch_table    分支事务状态                        │
     * │   └── lock_table      AT 模式全局锁                      │
     * ├─────────────────────────────────────────────────────────┤
     * │ 订单服务数据库 (order_db)                                │
     * │   ├── order           业务表                             │
     * │   ├── undo_log        AT 回滚日志                        │
     * │   └── tcc_fence_log   TCC 防悬挂 (如果用了 TCC)           │
     * ├─────────────────────────────────────────────────────────┤
     * │ 账户服务数据库 (account_db)                              │
     * │   ├── account         业务表                             │
     * │   ├── undo_log        AT 回滚日志                        │
     * │   └── tcc_fence_log   TCC 防悬挂 (如果用了 TCC)           │
     * ├─────────────────────────────────────────────────────────┤
     * │ 库存服务数据库 (stock_db)                                │
     * │   ├── stock           业务表                             │
     * │   └── undo_log        AT 回滚日志                        │
     * └─────────────────────────────────────────────────────────┘
     *
     * 🧠 记忆口诀:
     *   TC 三张: global、branch、lock    → Seata Server 库
     *   RM 两张: undo_log、tcc_fence     → 每个业务库
     * </pre>
     */
    String tableDistributionSummary = "Seata Server 库: global_table + branch_table + lock_table\n" +
            "每个业务库:   undo_log (AT) + tcc_fence_log (TCC, 可选)";
}
