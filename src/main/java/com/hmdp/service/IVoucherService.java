package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由VoucherServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 通过VoucherMapper的关联SQL取商铺优惠券，并附加秒杀库存/时间；当前没有单独缓存该列表。
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 券与秒杀活动记录在同一个MySQL事务中保存；提交回调使用MSET同时预热Redis库存、开始和结束毫秒值。
     * ZoneId.systemDefault用于把活动LocalDateTime转换为绝对时间，Lua随后使用Redis TIME判断活动区间。
     * 回调出错不会回滚已提交数据库，记录日志后该券在Redis未就绪时不可购买。
     */
    void addSeckillVoucher(Voucher voucher);
}
