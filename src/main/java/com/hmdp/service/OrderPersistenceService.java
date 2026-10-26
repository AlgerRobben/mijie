package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.SeckillOrderTask;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.repository.SeckillOrderTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单数据库事务边界：任务记录、条件扣MySQL库存、订单插入与SUCCEEDED在同一事务中提交。
 * 正常创建与最终失败核对都锁住同一任务行，防止退还Redis预占后旧消息再次创建订单。Redis结果与ACK在本类事务返回后执行。
 */
@Service
public class OrderPersistenceService {
    private final SeckillOrderTaskRepository tasks;
    private final VoucherOrderMapper orders;
    private final ISeckillVoucherService vouchers;

    public OrderPersistenceService(SeckillOrderTaskRepository tasks, VoucherOrderMapper orders, ISeckillVoucherService vouchers) {
        this.tasks = tasks; this.orders = orders; this.vouchers = vouchers;
    }

    /**
     * 同一MySQL事务内先锁任务行，已SUCCEEDED或FAILED直接返回，形成重投幂等/失败围栏。
     * 再检查同ID订单及同用户同券已有订单，条件更新stock>0后插入订单并标记成功。任一步抛异常都会回滚本事务库存和订单。
     */
    @Transactional(timeout = 10)
    public SeckillOrderTask persist(VoucherOrder order) {
        // 在任务表新增任务行并上行锁
        SeckillOrderTask task = tasks.lock(order);
        // 若task已成功或已失败直接返回
        if (task.succeeded() || "FAILED".equals(task.state())) {
            return task;
        }
        // 先查订单是否之前已落库，不重复落库，幂等操作
        // 从orderID角度查重
        VoucherOrder sameId = orders.selectById(order.getId());
        if (sameId != null) {
            // 检查已有订单的userID和voucherID是否正确
            verifyOwner(sameId, order);
            // 返回已成功处理
            return tasks.succeed(order.getId());
        }
        // 检查之前是否以其他orderID购买过
        // 一人一单限制本次任务处理失败
        VoucherOrder existing = existingPurchase(order);
        if (existing != null) {
            // 返回错误
            return tasks.fail(order.getId(), "ALREADY_PURCHASED", existing.getId());
        }
        // 库存检查与扣减处于同一SQL更新，stock>0条件抵御并发超卖；后续失败由同一事务回滚。
        boolean deducted = vouchers.update().setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        if (!deducted) {
            throw new IllegalStateException("MySQL stock unavailable");
        }
        // orderID作primary key
        // userID + voucherID作UNIQUE KEY
        // 限制一人一单
        if (orders.insert(order) != 1) {
            throw new IllegalStateException("Order insert failed");
        }
        return tasks.succeed(order.getId());
    }

    /**
     * 最终失败核对事务，与persist锁同一行。任务或订单已成功则返回成功，不允许补偿已售库存。
     * 确认没有这次订单才写FAILED；该记录提交后，即使旧消费者迟到也不能再创建这个ID。数据库不可用时抛异常，不猜测结果。
     */
    @Transactional(timeout = 10)
    public SeckillOrderTask fenceFailure(VoucherOrder order) {
        // 订单处理任务上行锁。相当于取得该订单的处理锁
        SeckillOrderTask task = tasks.lock(order);
        if (task.succeeded()) {
            return task;
        }
        // 查订单记录是否已落库，要求订单信息中orderID userID voucherID全部匹配
        VoucherOrder sameId = orders.selectById(order.getId());
        if (sameId != null) {
            verifyOwner(sameId, order);
            // task处理状态变为SUCCEED并返回
            return tasks.succeed(order.getId());
        }
        if ("FAILED".equals(task.state())) {
            return task;
        }
        // 走到这里已经确认FAILED，但需要确定FAILED原因
        // existing存在说明之前校验资格判断有问题，订单表中已由对应userID和voucherID的记录落库，但是orderID不同
        // existing不存在则说明单纯是本次订单处理任务失败
        VoucherOrder existing = existingPurchase(order);
        return tasks.fail(order.getId(), existing == null ? "CREATION_FAILED" : "ALREADY_PURCHASED",
                existing == null ? null : existing.getId());
    }

    /**
     * 按userId/voucherId查询已提交购买记录，与uk_user_voucher唯一索引的业务维度一致。
     * 这个检查用于解释结果，真正防并发重复还需要数据库唯一约束。
     */
    private VoucherOrder existingPurchase(VoucherOrder order) {
        return orders.selectOne(new QueryWrapper<VoucherOrder>().eq("user_id", order.getUserId())
                .eq("voucher_id", order.getVoucherId()));
    }

    /**
     * 同一订单ID必须对应同一个用户和券，防止错误/人工重放消息借别人的订单冒充成功。
     */
    private static void verifyOwner(VoucherOrder saved, VoucherOrder requested) {
        if (!saved.getUserId().equals(requested.getUserId()) || !saved.getVoucherId().equals(requested.getVoucherId())) {
            throw new IllegalStateException("Stored order identity differs from stream message");
        }
    }
}
