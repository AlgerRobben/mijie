package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 秒杀申请与最终创建状态的HTTP接口，登录身份由拦截器准备；不提供完整支付/核销/退款流程。
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * POST /voucher-order/seckill/{id}接受秒杀申请；返回字符串受理ID，最终结果需另查。
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * GET /voucher-order/status/{id}查询自己的申请，服务会校验当前用户归属。
     */
    @GetMapping("status/{id}")
    public Result queryOrderStatus(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }
}
