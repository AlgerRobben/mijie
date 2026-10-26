package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderTask;
import com.hmdp.entity.VoucherOrder;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由VoucherOrderServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * HTTP秒杀受理入口，用户身份由登录拦截器提供。生成业务ID后，Lua原子检查时间/库存/一人一券并预占、写Stream。
     * 返回的字符串ID仅表示Redis已受理，不等于MySQL订单成功，也避免浏览器大整数精度丢失。最终结果通过状态接口查询。
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 转交OrderPersistenceService的Spring事务代理，避免同类内部直接调用使@Transactional失效。
     */
    SeckillOrderTask handleVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 核对ID基本格式，再携当前登录用户查询创建状态；归属验证由OrderResultService执行。
     */
    Result queryOrderStatus(Long orderId);
}
