package com.hmdp.dto;

/**
 * 订单创建结果DTO。订单/券ID使用字符串以避免浏览器Number超过安全整数范围丢精度；message解释当前处理阶段。
 */
public record OrderCreationResult(String orderId, String voucherId, OrderCreationState state, String message) {}
