package com.axon.java.stack.face.simple;

/**
 * 题8 模板方法
 * 思路：把固定的流程骨架放到抽象父类里，接入的人继承后必须把几个空填上才能编译过
 */
public class OrderFlowTemplate {

    /** 请求体，实际项目里就是各个接入方自己定义的入参对象 */
    static class OrderRequest {
        private String requestId;
        private int amount;

        OrderRequest(String requestId, int amount) {
            this.requestId = requestId;
            this.amount = amount;
        }

        public String getRequestId() {
            return requestId;
        }

        public int getAmount() {
            return amount;
        }

        @Override
        public String toString() {
            return "OrderRequest{requestId=" + requestId + ", amount=" + amount + "}";
        }
    }

    /** 统一的返回结果 */
    static class Result {
        boolean success;
        String msg;

        Result(boolean success, String msg) {
            this.success = success;
            this.msg = msg;
        }

        @Override
        public String toString() {
            return (success ? "[成功] " : "[失败] ") + msg;
        }
    }

    /**
     * 抽象模板，泛型T就是每种业务各自的请求体
     * 子类必须实现下面三个方法：校验参数、正常业务、异常处理，handle 用 final 锁死不让子类改流程
     */
    abstract static class BaseHandler<T> {

        // 对外的入口，流程就四步：校验 -> 业务 -> 异常 -> 打日志摘要
        public final Result handle(T request) {
            try {
                checkParam(request);              // 第一步：请求体校验
                String data = doBusiness(request);  // 第二步：正常业务
                return new Result(true, data);
            } catch (Exception e) {               // 第三步：业务异常统一在这兜住
                onError(e.getMessage());
                return new Result(false, e.getMessage());
            } finally {
                // 第四步：日志摘要，放finally保证失败也打
                log("执行完毕 " + request);
            }
        }

        protected abstract void checkParam(T request);

        protected abstract String doBusiness(T request);

        protected abstract void onError(String msg);

        void log(String msg) {
            System.out.println(this.getClass().getSimpleName() + " --> " + msg);
        }
    }

    /** 下单的处理类，只需要实现自己的那几步 */
    static class CreateOrderHandler extends BaseHandler<OrderRequest> {

        @Override
        protected void checkParam(OrderRequest request) {
            if (request.getRequestId() == null || request.getRequestId().isEmpty()) {
                throw new IllegalArgumentException("请求id不能为空");
            }
            if (request.getAmount() <= 0) {
                throw new IllegalArgumentException("金额必须大于0");
            }
        }

        @Override
        protected String doBusiness(OrderRequest request) {
            // 题目说不用真实实现，这里打印模拟一下就行
            return "下单成功，订单号DO" + System.currentTimeMillis();
        }

        @Override
        protected void onError(String msg) {
            System.out.println("出错了，记个日志再告警: " + msg);
        }
    }

    /** 退款的请求体，另一种业务长不一样 */
    static class RefundRequest {
        private String orderId;
        private String reason;

        RefundRequest(String orderId, String reason) {
            this.orderId = orderId;
            this.reason = reason;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "RefundRequest{orderId=" + orderId + "}";
        }
    }

    /** 退款的处理类，同一个模板，请求体和逻辑换成自己的 */
    static class RefundHandler extends BaseHandler<RefundRequest> {

        @Override
        protected void checkParam(RefundRequest request) {
            if (request.getOrderId() == null || request.getReason() == null || request.getReason().isEmpty()) {
                throw new IllegalArgumentException("订单号和退款原因不能为空");
            }
        }

        @Override
        protected String doBusiness(RefundRequest request) {
            if ("余额不足".equals(request.getReason())) {
                throw new IllegalStateException("账户余额不足以发起退款");
            }
            return "退款成功";
        }

        @Override
        protected void onError(String msg) {
            System.out.println("出错了，记个日志再告警: " + msg);
        }
    }

    public static void main(String[] args) {
        BaseHandler<OrderRequest> orderHandler = new CreateOrderHandler();
        BaseHandler<RefundRequest> refundHandler = new RefundHandler();

        System.out.println(orderHandler.handle(new OrderRequest("req-001", 99)));

        System.out.println();

        // 金额不合法，走异常分支
        System.out.println(orderHandler.handle(new OrderRequest("req-002", -5)));

        System.out.println();

        System.out.println(refundHandler.handle(new RefundRequest("DO8888", "不想要了")));
    }
}
