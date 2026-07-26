package com.axon.java.stack.seata;

/**
 * <h2>Saga 模式 — SpringBoot 配置 + 代码示例</h2>
 *
 * <h3>Saga 两种用法</h3>
 * <pre>
 * 1. 状态机编排 (推荐) — JSON DSL 定义流程，Seata Server 驱动
 * 2. 注解方式 — 代码中声明每一步
 * </pre>
 */
public class SagaModeDemo {

    /**
     * <h3>方式一: JSON 状态机编排 (推荐)</h3>
     *
     * <pre>
     * Seata Server(TC) 读取 JSON 配置，按状态驱动流程:
     *
     * {
     *   "Name": "出差审批流程",
     *   "StartState": "SubmitApplication",
     *   "States": {
     *     "SubmitApplication": {
     *       "Type": "ServiceTask",
     *       "ServiceName": "travelService.submit",
     *       "Next": "ManagerApprove",
     *       "CompensateState": "CancelApplication"
     *     },
     *     "ManagerApprove": {
     *       "Type": "ServiceTask",
     *       "ServiceName": "managerService.approve",
     *       "Next": "FinanceApprove",
     *       "CompensateState": "ManagerReject"
     *     },
     *     "FinanceApprove": {
     *       "Type": "ServiceTask",
     *       "ServiceName": "financeService.approve",
     *       "Next": "BookFlight",
     *       "CompensateState": "FinanceReject"
     *     },
     *     "BookFlight": {
     *       "Type": "ServiceTask",
     *       "ServiceName": "flightService.book",
     *       "Next": "BookHotel",
     *       "CompensateState": "CancelFlight"
     *     },
     *     "BookHotel": {
     *       "Type": "ServiceTask",
     *       "ServiceName": "hotelService.book",
     *       "CompensateState": "CancelHotel"
     *     }
     *   }
     * }
     *
     * 执行:
     *   submit → manager approve → finance approve → book flight → book hotel ✓
     *   如果 book hotel 失败 → cancel flight → finance reject → manager reject → cancel application
     *
     * TC 驱动整个流程:
     *   - 每步成功 → 推进到下一步
     *   - 某步失败 → 逆序执行已完成步骤的 CompensateState
     *   - 全部补偿完 → 结束
     * </pre>
     */
    public static void jsonStateMachine() {
    }

    /**
     * <h3>方式二: 注解方式</h3>
     *
     * <pre>
     * 每一步声明 Positive 方法（正向）和 Negative 方法（补偿）
     *
     * @GlobalTransactional
     * public void travelApproval(TravelRequest req) {
     *     // 步骤1: 提交申请
     *     travelService.submit(req);     // 补偿: travelService.cancel(req)
     *
     *     // 步骤2: 主管审批
     *     managerService.approve(req);   // 补偿: managerService.reject(req)
     *
     *     // 步骤3: 财务审批
     *     financeService.approve(req);   // 补偿: financeService.reject(req)
     *
     *     // 步骤4: 订机票
     *     flightService.book(req);       // 补偿: flightService.cancel(req)
     *
     *     // 步骤5: 订酒店
     *     hotelService.book(req);        // 补偿: hotelService.cancel(req)
     *
     *     // 如果步骤5失败:
     *     //   自动逆序执行: cancelFlight → financeReject → managerReject → cancelApplication
     *     //   每步独立提交，无锁
     * }
     * </pre>
     */
    public static class TravelSagaController {
    }

    /**
     * <h3>Saga 和 TCC 最直观的区别</h3>
     *
     * <pre>
     * TCC (两阶段):
     *   第一步 Try(申请阶段):    冻结 100 元
     *   第二步 Confirm/Cancel:  真的扣 / 解冻
     *
     * Saga (多步):
     *   第一步: 提交申请     → 失败了补偿: 取消申请
     *   第二步: 主管审批     → 失败了逆序补偿: 主管撤销 + 取消申请
     *   第三步: 财务审批     → 失败了逆序补偿: 财务撤销 + 主管撤销 + 取消申请
     *   每一步都有独立的成功和失败状态
     *
     * TCC 像"签合同": 双方先签字冻结确认, 最后统一盖章
     * Saga 像"接力赛": 每    接下一棒, 谁掉棒了, 后面的不用跑了, 前面的往回跑
     * </pre>
     */
    public static void sagaVsTcc() {
    }

    /**
     * <h3>Saga 适用场景</h3>
     *
     * <pre>
     * ✅ 流程特别长 (5步以上)
     * ✅ 跨系统, 每个环节都是独立的微服务
     * ✅ 不要求强隔离性 (中间状态允许被外部看到)
     * ✅ 不需要冻结/锁资源
     *
     * ❌ 高并发抢资源 (Saga 无锁, 防不了超卖)
     * ❌ 要求中间状态不可见
     * ❌ 步数少 (2-3步直接上 TCC 或 AT, 没必要 Saga)
     * </pre>
     */
    public static void sagaUseCases() {
    }
}
