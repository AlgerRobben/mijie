package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 优惠券与秒杀活动创建。先在同一MySQL事务保存券与活动库存，提交后才把库存和活动边界预热到Redis。
 * Redis预热失败会记录日志，库存Lua拒绝未初始化的券；不能用数据库旧库存直接覆盖正在使用的Redis计数。
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 通过VoucherMapper的关联SQL取商铺优惠券，并附加秒杀库存/时间；当前没有单独缓存该列表。
     */
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    /**
     * 券与秒杀活动记录在同一个MySQL事务中保存；提交回调使用MSET同时预热Redis库存、开始和结束毫秒值。
     * ZoneId.systemDefault用于把活动LocalDateTime转换为绝对时间，Lua随后使用Redis TIME判断活动区间。
     * 回调出错不会回滚已提交数据库，记录日志后该券在Redis未就绪时不可购买。
     */
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        if (!save(voucher)) {
            throw new IllegalStateException("保存优惠券失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("保存秒杀库存失败");
        }
        // Only expose Redis stock after the database transaction has committed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ZoneId zone = ZoneId.systemDefault();
                    stringRedisTemplate.opsForValue().multiSet(Map.of(
                            SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString(),
                            SECKILL_BEGIN_KEY + voucher.getId(), Long.toString(voucher.getBeginTime().atZone(zone).toInstant().toEpochMilli()),
                            SECKILL_END_KEY + voucher.getId(), Long.toString(voucher.getEndTime().atZone(zone).toInstant().toEpochMilli())));
                } catch (RuntimeException e) {
                    // Missing stock makes the Lua script reject orders rather than oversell.
                    log.error("秒杀库存预热失败，优惠券暂不可购买: {}", voucher.getId(), e);
                }
            }
        });
    }
}
