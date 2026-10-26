package com.hmdp.dto;

/**
 * 对用户展示的订单创建过程状态，与VoucherOrder.status的支付/核销/退款状态分开。
 * PENDING已受理、RETRYING仍重试、SUCCEEDED已提交、COMPENSATING等待核对/释放、FAILED最终失败且释放完成。
 */
public enum OrderCreationState {
    PENDING, RETRYING, SUCCEEDED, COMPENSATING, FAILED
}
