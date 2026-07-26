> **总结日期：** 2026-07-07
> **用途：** Seata 面试题全集，面试前通读一遍

---

## 基础篇

### Q1: Seata 是什么？解决了什么问题？

> Seata 是阿里巴巴开源的分布式事务解决方案。解决微服务架构下，跨多个数据库/服务的数据一致性问题。比如下单同时扣库存、扣余额，三个服务三个数据库，要么全部成功，要么全部失败。

### Q2: Seata 有哪三大角色？

| 角色 | 全称 | 在哪 | 职责 |
|---|---|---|---|
| **TC** | Transaction Coordinator | Seata Server（独立部署） | 维护全局事务状态，协调提交/回滚 |
| **TM** | Transaction Manager | 业务入口微服务 | 开启/结束全局事务（加 @GlobalTransactional 的那个） |
| **RM** | Resource Manager | 每个参与微服务 | 管理本地资源，汇报分支状态，执行提交/回滚 |

### Q3: Seata 有哪几种模式？怎么选？

| 模式 | 一句话 | 一致性 | 性能 | 侵入性 | 适用场景 |
|---|---|---|---|---|---|
| **AT** | 先提交，记反向SQL，错了倒着执行 | 最终一致 | 好 | 低（undo_log 表） | 通用业务（默认） |
| **XA** | 数据库锁住等通知 | 强一致 | 差 | 无 | 强一致金融 |
| **TCC** | 手写 Try/Confirm/Cancel | 最终一致 | 好 | 高（三套代码） | 有第三方接口/非DB资源 |
| **Saga** | 正向编排 + 逆序补偿 | 最终一致 | 好 | 中 | 长流程/跨系统 |

### Q4: @GlobalTransactional 加在哪？是入口还是所有服务？

> 只加在全局事务的**入口方法**上（TM）。下游微服务不用加，Seata 通过 RPC 框架自动传递 XID，下游自动成为 RM。

### Q5: AT 模式需要哪些配置？完整步骤？

1. pom.xml 引入 `seata-spring-boot-starter`
2. application.yml 配置注册中心、配置中心地址
3. 配置 `seata.data-source-proxy-mode: AT`
4. 每个业务库建 `undo_log` 表
5. 业务方法加 `@GlobalTransactional`

---

## AT 模式篇

### Q6: AT 模式的原理是什么？

> 两阶段提交：
> **一阶段：** 执行 SQL → 直接提交（释放本地锁） → 记 undo_log（反向 SQL）
> **二阶段：** 全部成功 → 删 undo_log；有失败 → 执行 undo_log 反向 SQL → 删 undo_log

### Q7: AT 模式和 XA 模式的区别？

| | AT | XA |
|---|---|---|
| 一阶段 | SQL 直接提交 | SQL 执行但不提交，持锁 |
| 回滚 | 反向 SQL | 数据库原生 XA ROLLBACK |
| 锁 | 一阶段后释放行锁，加 TC 全局锁 | 数据库行锁贯穿全程 |
| 性能 | 好 | 差 |
| 需要 undo_log 表 | ✅ | ❌ |

### Q8: AT 模式的脏写问题是什么？怎么解决？

> **脏写：** A 事务一阶段提交后释放行锁，B 事务并发修改同一行，A 二阶段回滚把 B 的修改覆盖了。
>
> **解决：全局锁。** A 一阶段提交前向 TC 申请全局锁（表名 + 主键），B 更新同一行前检查全局锁，有锁则等待。A 二阶段完成后释放。

### Q9: AT 模式的全局锁存哪？TC 挂了锁还在吗？

> 存在 TC 内存 + `lock_table` 表（如果 TC 用 db 存储模式）。TC 挂了锁就丢了，所以 AT 不是强一致，是最终一致。XA 的一致性更强（锁在数据库里，TC 挂了锁还在）。

### Q10: AT 模式有什么局限？

> 只能回滚关系型数据库的 INSERT/UPDATE/DELETE。调第三方接口、发短信、Redis 缓存、MongoDB 等操作 AT 管不了，必须用 TCC。

---

## XA 模式篇

### Q11: XA 模式和 AT 代码上有区别吗？

> **代码完全一样。** 就 yml 里改一行：`data-source-proxy-mode: AT` → `data-source-proxy-mode: XA`

### Q12: XA 能跟 AT 混用吗？

> **不能。** `data-source-proxy-mode` 是全局配置，一个微服务只能选一个。但 XA + TCC 可以混用。

### Q13: 什么时候用 XA 不用 AT？

> 1. 对一致性要求极高（钱相关核心操作）
> 2. TC 宕机也要保证一致性（XA 的锁在数据库，TC不高能接受长锁

---

## TCC 模式篇

### Q14: TCC 的三个方法分别干什么？

| 方法 | 作用 | 示例（扣余额） |
|---|---|---|
| **Try** | 预留资源 + 检查 | 冻结 100 元（balance -= 100, frozen += 100） |
| **Confirm** | 确认执行 | 真正扣（frozen -= 100） |
| **Cancel** | 释放预留资源 | 解冻（frozen -= 100, balance += 100） |

### Q15: TCC 为什么需要冻结态？

> AT 直接提交 + 反SQL 的方式，在回滚前有中间窗口，并发请求可能读到中间数据。TCC 通过冻结态（如余额分离成 balance + frozen），Try 之后其他请求看到的是"冻结中"，无法使用，等 Confirm 才真正扣，等 Cancel 解冻回来。

### Q16: 什么场景必须用 TCC，不能用 AT？

> 操作不是数据库 SQL：调支付宝/微信扣款、发短信/邮件、Redis 缓存扣库存、MongoDB 写入。这些没有反向 SQL，AT 无能为力，必须手写 TCC 补偿。

### Q17: @TwoPhaseBusinessAction 注解怎么用？

```java
@TwoPhaseBusinessAction(
    name = "deductAccount",      // 全局唯一名称
    commitMethod = "confirm",    // Confirm 方法名
    rollbackMethod = "cancel",   // Cancel 方法名
    useTCCFence = true           // 开启防悬挂
)
public boolean tryDeduct(BusinessActionContext ctx, Long userId, Long amount);
```

### Q18: 什么是空回滚？怎么处理？

> **空回滚：** Cancel 先到了，Try 还没执行（或没成功）。Cancel 发现没有可释放的资源，直接返回成功即可。需要在 Cancel 方法里加判断（查 tcc_fence_log 或业务字段）。

### Q19: 什么是业务悬挂（防悬挂）？怎么处理？

> **悬挂：** Try 请求超时 → TM 发 Cancel → Cancel 先执行完 → Try 慢悠悠再到达并执行，资源被预留但无人释放。
>
> **防悬挂：** 用 `tcc_fence_log` 表，主键 `(xid, branch_id)`。Cancel 先到 → INSERT 记录占坑；Try 晚到 → INSERT 冲突 → Try 被拒绝。

### Q20: TCC 和 AT 能混用吗？

> **能。** 同一个 `@GlobalTransactional` 下，数据库操作用 AT，调第三方接口用 TCC，Seata 自动协调。调用方完全无感知。

---

## Saga 模式篇

### Q21: Saga 模式原理？

> 长事务拆分多步，每步独立提交。失败时逆序执行补偿步骤。无锁，不保证隔离性。

### Q22: Saga 和 TCC 的区别？

| | TCC | Saga |
|---|---|---|
| 阶段 | 两阶段 | 多步 |
| 资源预留 | Try 冻结 | 不预留 |
| 锁 | 有（冻结态） | 无 |
| 隔离性 | 好 | 差（中间结果可见） |
| 步数 | 2-3 步 | 5+ 步 |
| 适用 | 高并发抢资源 | 长流程 |

### Q23: Saga 需要注解吗？

> **不需要。** Saga 由 Seata Server 的 JSON 状态机文件编排流程，业务代码只写普通方法。

---

## 表结构篇

### Q24: Seata 需要建哪些表？分别在哪个库？

| 表名 | 建在哪 | 作用 |
|---|---|---|
| `global_table` | Seata Server 库 | 全局事务状态 |
| `branch_table` | Seata Server 库 | 分支事务状态 |
| `lock_table` | Seata Server 库 | AT 全局锁 |
| `undo_log` | 每个业务库 | AT 反向 SQL |
| `tcc_fence_log` | 每个用 TCC 的业务库 | TCC 防悬挂 |

### Q25: undo_log 表存什么？

> 存反向 SQL 的 JSON。比如 `UPDATE account SET balance = balance - 100` 的正向 SQL，undo_log 里存 `UPDATE account SET balance = balance + 100`。回滚时执行这个反向 SQL。

### Q26: tcc_fence_log 防悬挂的核心原理？

> 主键 `(xid, branch_id)`。同一个全局事务的同一个分支只能插入一条记录。Try 重试 → INSERT 冲突 → 被拒绝，保证 Try 只执行一次。

---

## 实战篇

### Q27: 聚合支付场景怎么选模式？

```
AT:   创建订单、更新状态、记流水                    （纯 SQL）
TCC:  调支付宝/微信扣款、调银行打款、调风控接口      （第三方 API）
XA:   跨行资金清算等强一致场景（用得少）             （强一致）
Saga: 结算打款长流程（申请→审核→打款）              （长事务）
```

> 聚合支付标准配置：**AT + TCC 混用**，90% 场景够了。

### Q28: 微服务下 Seata 怎么传递事务上下文？

> Seata 自动通过 RPC 框架（Dubbo Filter / Feign Interceptor）把 XID 塞进请求头。下游拦截到 XID 后向 TC 注册自己为分支事务。业务代码无感知。

### Q29: Seata Server 挂了怎么办？

> - AT 模式：服务继续运行，但新事务无法开启。已开启的事务不受影响（undo_log 在本地）。但全局锁丢失，可能有脏写风险。
> - XA 模式：服务继续，新事务无法开启。已开启的事务二阶段会阻塞。
> - 生产必须部署 Seata Server 集群 + DB 存储模式。

### Q30: Seata 性能瓶颈在哪？

> 1. TC 单点 → 部署集群
> 2. 全局锁竞争 → AT 模式下 UPDATE 热点数据时排队
> 3. undo_log 膨胀 → 定时清理已提交的日志
> 4. TCC 空回滚/悬挂的 fence_log 写入 → 已在本地，影响不大

---

## 总结口诀

```
XA锁着等，慢但稳
AT先交差，错再改
TCC三部曲，防悬空
Saga长流水，逆序补

TC三张: global、branch、lock
RM两张: undo、fence
```
