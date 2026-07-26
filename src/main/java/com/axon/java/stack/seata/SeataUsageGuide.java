package com.axon.java.stack.seata;

/**
 * <h2>Seata 四种模式在 Spring Boot 中的使用流程</h2>
 *
 * <pre>
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  本文档演示: AT / XA / TCC / Saga 的完整配置 + 代码模板      ║
 * ║  一条全局事务中可以混合使用不同模式                           ║
 * ╚══════════════════════════════════════════════════════════════╝
 * </pre>
 *
 * <h3>【AT 模式 — 使用流程】</h3>
 * <pre>
 * 1. 引入依赖: seata-spring-boot-starter
 * 2. application.yml 配置 seata 注册中心、配置中心
 * 3. 每个业务数据库建 undo_log 表
 * 4. 配置 Seata 数据源代理 (seata.data-source-proxy 或手动 DataSourceProxy)
 * 5. 业务方法加 @GlobalTransactional
 * 6. 什么都不用管，SQL 自动归 AT 管理
 * </pre>
 *
 * <h3>【XA 模式 — 使用流程】</h3>
 * <pre>
 * 1. 同上引入依赖
 * 2. application.yml 中 data-source-proxy-mode 改为 XA
 * 3. 业务方法加 @GlobalTransactional
 * 4. 数据库必须支持 XA 协议 (MySQL InnoDB, Oracle, PostgreSQL)
 * </pre>
 *
 * <h3>【TCC 模式 — 使用流程】</h3>
 * <pre>
 * 1. 同上引入依赖
 * 2. 接口方法上加 @TwoPhaseBusinessAction 声明 Try 方法
 * 3. 实现类里写 Try / Confirm / Cancel 三个方法
 * 4. 业务方法加 @GlobalTransactional
 * 5. TCC 和 AT 可以混在同一个 @GlobalTransactional 里
 * </pre>
 *
 * <h3>【Saga 模式 — 使用流程】</h3>
 * <pre>
 * 1. 优先用 Seata Server 的状态机编排 (JSON/DSL 定义流程)
 * 2. 或写注解方式，每个步骤声明正向方法和补偿方法
 * 3. 业务方法加 @GlobalTransactional
 * </pre>
 *
 * @see AtModeDemo      AT 模式示例
 * @see XaModeDemo      XA 模式示例
 * @see TccModeDemo     TCC 模式示例
 * @see SagaModeDemo    Saga 模式示例
 */
public class SeataUsageGuide {
}
