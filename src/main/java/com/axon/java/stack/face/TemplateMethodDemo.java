package com.axon.java.stack.face;

import java.util.UUID;

import java.util.UUID;

/**
 * <h2>面试题 8: 模板方法模式 —— 统一的应用层接入骨架</h2>
 *
 * <pre>
 * 题目要求:
 *   设计一个方式, 使得开发者在应用层接入时【必须】实现相关功能, 包含:
 *     1. 请求体校验
 *     2. 正常业务处理
 *     3. 业务异常处理
 *     4. 日志摘要打印 (通过打印日志实现即可, 不需要真实业务)
 *
 * 设计思路 (模板方法模式):
 *   父类 AbstractRequestHandler 把"流程骨架"固化在 final 的 handle() 方法里:
 *        校验 → 业务处理 → 异常兜底 → 日志摘要
 *
 *   - 必须实现的步骤 → 声明为 abstract 方法, 应用层不实现就编译不过 (强约束)
 *   - 可选实现的步骤 → 父类给出默认实现 (钩子方法 hook), 子类按需覆盖
 *
 *   这样框架层面保证了:
 *     a) 所有接入方的处理顺序完全一致 (没人能跳过校验直接跑业务)
 *     b) 异常统一收口, 不会漏打日志
 *     c) 接入方只关心自己的业务差异部分 (开闭原则)
 * </pre>
 */
public class TemplateMethodDemo {

    // ==================== 公共实体 ====================

    /**
     * 模拟请求体
     */
    public static class OrderRequest {

        private final String requestId;
        private final Long userId;
        private final Integer amount;

        public OrderRequest(String requestId, Long userId, Integer amount) {
            this.requestId = requestId;
            this.userId = userId;
            this.amount = amount;
        }

        public String getRequestId() {
            return requestId;
        }

        public Long getUserId() {
            return userId;
        }

        public Integer getAmount() {
            return amount;
        }
    }

    /**
     * 统一的业务异常: 参数非法和业务规则失败都用它, 带 errorCode 方便下游分类处理
     */
    public static class BizException extends RuntimeException {

        private final String errorCode;

        public BizException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * 统一的返回结果
     */
    public static class HandleResult {

        private final boolean success;
        private final String message;
        private final Object data;

        private HandleResult(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public static HandleResult ok(Object data) {
            return new HandleResult(true, "SUCCESS", data);
        }

        public static HandleResult fail(String errorCode, String message) {
            return new HandleResult(false, errorCode + ": " + message, null);
        }

        @Override
        public String toString() {
            return "HandleResult{success=" + success + ", message='" + message + "', data=" + data + "}";
        }
    }

    // ==================== 核心抽象模板 ====================

    /**
     * <h3>抽象模板: 应用层接入的统一骨架</h3>
     *
     * @param <T> 具体的请求体类型, 不同接入方可定义各自的请求对象
     */
    public abstract static class AbstractRequestHandler<T> {

        /**
         * <b>模板方法</b>: final 修饰, 子类只能扩展不能改变流程骨架。
         *
         * <pre>
         * 流程固定为四步, 并保证 finally 里一定打印日志摘要 (异常也不丢日志):
         *
         *   [1] validate  请求体校验   → 不合法直接短路返回, 业务不会被执行
         *   [2] doProcess 正常业务处理 → 成功则包装 HandleResult.ok 返回
         *   [3] onBizError 业务异常处理→ 被 catch 收口, 统一转成失败结果
         *   [4] printSummary 日志摘要  → 无论成败都会执行
         * </pre>
         */
        public final HandleResult handle(T request) {
            long start = System.currentTimeMillis();
            try {
                validate(request);
                Object data = doProcess(request);
                return HandleResult.ok(data);
            } catch (BizException e) {
                onBizError(request, e);
                return HandleResult.fail(e.getErrorCode(), e.getMessage());
            } finally {
                printSummary(request, System.currentTimeMillis() - start);
            }
        }

        /**
         * 【必须实现】请求体校验
         * 校验失败请抛出 BizException, 由模板统一捕获
         */
        protected abstract void validate(T request);

        /**
         * 【必须实现】正常业务处理
         * 返回业务数据; 内部遇到业务规则失败同样抛 BizException
         */
        protected abstract Object doProcess(T request);

        /**
         * 【必须实现】业务异常处理
         * 例如: 补偿、告警、写失败流水等 (此处按题目要求仅打印日志模拟)
         */
        protected abstract void onBizError(T request, BizException e);

        /**
         * 【可选覆盖】日志摘要打印 — 默认实现已满足题目要求 (钩子方法)
         * 需要更详细埋点 (traceId / 上报监控) 时再由子类覆盖
         */
        protected void printSummary(T request, long costMs) {
            System.out.println("[摘要] handler=" + this.getClass().getSimpleName()
                    + ", request=" + request + ", cost=" + costMs + "ms");
        }
    }

    // ==================== 应用层接入示例 1: ====================

    /**
     * 应用层开发者只需要关心四个抽象方法的差异化实现, 无需触碰流程编排
     */
    public static class OrderCreateHandler extends AbstractRequestHandler<OrderRequest> {

        @Override
        protected void validate(OrderRequest request) {
            System.out.println("[下单] step1 请求体校验...");
            if (request.getUserId() == null || request.getAmount() == null || request.getAmount() <= 0) {
                throw new BizException("PARAM_ERROR", "userId 不能为空且金额必须大于 0");
            }
        }

        @Override
        protected Object doProcess(OrderRequest request) {
            System.out.println("[下单] step2 正常业务处理: 创建订单, amount=" + request.getAmount());
            return "订单号-" + UUID.randomUUID().toString().substring(0, 8);
        }

        @Override
        protected void onBizError(OrderRequest request, BizException e) {
            System.out.println("[下单] step3 业务异常处理: errorCode=" + e.getErrorCode()
                    + ", msg=" + e.getMessage());
        }
    }

    // ==================== 应用层接入示例 2: 退款 ====================

    /**
     * 第二个接入方: 同一套骨架, 只替换业务细节
     * 另外演示 doProcess 内部抛业务异常的路径 + 覆盖钩子方法
     */
    public static class RefundHandler extends AbstractRequestHandler<OrderRequest> {

        @Override
        protected void validate(OrderRequest request) {
            System.out.println("[退款] step1 请求体校验...");
            if (request.getRequestId() == null || request.getRequestId().trim().isEmpty()) {
                throw new BizException("PARAM_ERROR", "原订单号不能为空");
            }
        }

        @Override
        protected Object doProcess(OrderRequest request) {
            System.out.println("[退款] step2 正常业务处理: 校验原订单并退款");
            // 模拟真实场景中的业务规则失败
            throw new BizException("REFUND_BALANCE_NOT_ENOUGH", "账户余额不足以发起退款");
        }

        @Override
        protected void onBizError(OrderRequest request, BizException e) {
            System.out.println("[退款] step3 业务异常处理: 记录退款失败流水, msg=" + e.getMessage());
        }

        /**
         * 覆盖钩子方法, 打印更详细的摘要信息
         */
        @Override
        protected void printSummary(OrderRequest request, long costMs) {
            System.out.println("[摘要-定制版] 退款单=" + request.getRequestId()
                    + ", 结果耗时=" + costMs + "ms, 触发告警埋点");
        }
    }

    // ==================== 运行验证 ====================

    public static void main(String[] args) {
        AbstractRequestHandler<OrderRequest> orderHandler = new OrderCreateHandler();
        AbstractRequestHandler<OrderRequest> refundHandler = new RefundHandler();

        System.out.println("======== 场景1: 参数非法 (校验阶段被拦截) ========");
        System.out.println(orderHandler.handle(new OrderRequest("R001", null, -5)));

        System.out.println("\n======== 场景2: 正常业务处理成功 ========");
        System.out.println(orderHandler.handle(new OrderRequest("R002", 1001L, 199)));

        System.out.println("\n======== 场景3: 业务处理阶段抛业务异常 ========");
        System.out.println(refundHandler.handle(new OrderRequest("R003", 1001L, 88)));
    }
}
