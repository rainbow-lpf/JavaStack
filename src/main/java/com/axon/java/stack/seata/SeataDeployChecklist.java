package com.axon.java.stack.seata;

/**
 * <h2>Seata 启动前 check list</h2>
 *
 * <pre>
 * ╔══════════════════════════════════════════════════════════════╗
 * ║           部署 Seata 的必备清单                               ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * 【环境准备】
 * □ JDK 8+
 * □ MySQL 5.7+ (或 MariaDB 10.2+)
 * □ Nacos 2.x 或 Zookeeper (作为注册中心 + 配置中心)
 *
 * 【Seata Server 部署】
 * □ 下载 seata-server-1.6.1.zip
 * □ 解压, 修改 conf/application.yml:
 *     - 注册中心类型 (nacos/zk/eureka)
 *     - 配置中心类型 (nacos/zk/file)
 *     - 存储模式: db (推荐生产用) 或 file (开发调试)
 * □ 如果选 db 存储: 创建 seata 数据库, 执行 db_store.sql
 *     CREATE DATABASE seata;
 *     source conf/db_store.sql;
 * □ 启动: sh bin/seata-server.sh (Linux) 或 bin/seata-server.bat (Win)
 *
 * 【每个微服务配置】
 * □ pom.xml 加 seata-spring-boot-starter
 * □ application.yml 配 seata 注册中心 + 配置中心地址
 * □ 指定 data-source-proxy-mode: AT 或 XA
 *
 * 【AT 模式额外步骤】
 * □ 每个业务数据库执行 undo_log 建表 SQL
 *
 * 【TCC 模式额外步骤】
 * □ 每个业务数据库执行 tcc_fence_log 建表 SQL
 * □ 接口加 @TwoPhaseBusinessAction 注解
 * □ 实现 Try / Confirm / Cancel 三个方法
 *
 * 【XA 模式额外步骤】
 * □ data-source-proxy-mode: XA
 * □ 确认数据库支持 XA (MySQL InnoDB 默认支持)
 *
 * 【验证】
 * □ Seata Server 正常启动: curl http://localhost:7091
 * □ 微服务启动后看日志: 是否有 "register TM success" 字样
 * □ 触发一个故意失败的业务，检查 undo_log 是否回滚
 * </pre>
 */
public class SeataDeployChecklist {
}
