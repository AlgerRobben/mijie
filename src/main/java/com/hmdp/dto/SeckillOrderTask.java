package com.hmdp.dto;

/**
 * 数据库订单创建任务的不可变快照。state是创建事实，existingOrderId指同用户已有的另一订单，compensated描述是否还有释放工作。
 * SUCCEEDED任务也设compensated=true，表示无需补偿，不表示发生了退款；FAILED任务的false表示围栏已提交但释放尚未完成。
 */
public record SeckillOrderTask(long orderId, long userId, long voucherId, String state,
                               String reason, Long existingOrderId, boolean compensated) {
    /**
     * 仅根据任务state是否为SUCCEEDED判断已提交的创建结果。
     */
    public boolean succeeded() {
        return "SUCCEEDED".equals(state);
    }
}
