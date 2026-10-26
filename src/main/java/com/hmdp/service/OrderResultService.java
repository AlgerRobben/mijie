package com.hmdp.service;

import com.hmdp.dto.*;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.repository.SeckillOrderTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 协调持久化订单创建事实与Redis状态/预占。查询先核对MySQL订单和任务，并验证请求用户归属。
 * SUCCEEDED表示订单已提交，不表示支付/核销完成；FAILED只有在失败记录和释放流程已完成后才作为外部终态返回。
 * 数据库不可用时不猜测订单失败，处理中的状态可以降级读取Redis，终态则要求数据库事实可核对。
 */
@Slf4j
@Service
public class OrderResultService {
    public static final String RESULT_PREFIX = "seckill:result:";
    private static final DefaultRedisScript<Long> SUCCESS = script("lua/order-result-success.lua");
    private static final DefaultRedisScript<Long> RETRY = script("lua/order-result-retry.lua");
    private static final DefaultRedisScript<Long> COMPENSATE = script("lua/compensate-order.lua");
    private final StringRedisTemplate redis;
    private final VoucherOrderMapper orders;
    private final SeckillOrderTaskRepository tasks;
    private final long terminalTtl;

    public OrderResultService(StringRedisTemplate redis, VoucherOrderMapper orders, SeckillOrderTaskRepository tasks,
                              @Value("${hmdp.seckill.terminal-status-ttl-seconds:604800}") long terminalTtl) {
        this.redis = redis; this.orders = orders; this.tasks = tasks; this.terminalTtl = terminalTtl;
    }

    /**
     * 加载classpath中的Redis Lua脚本，返回Long用于校验脚本的成功/拒绝结果。
     */
    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path)); script.setResultType(Long.class); return script;
    }

    /**
     * 把仍处理中状态更新为RETRYING并记录投递次数；Lua不会把已成功/最终失败覆盖成处理中。
     */
    public void retrying(VoucherOrder order, long attempts) {
        redis.execute(RETRY, List.of(RESULT_PREFIX + order.getId()), Long.toString(attempts));
    }

    /**
     * 根据已提交的数据库任务结果同步Redis：成功仅标记SUCCEEDED；失败必须有FAILED围栏才允许释放。
     * 补偿Lua确认预占属于这个ID，并写永久幂等收据，再把MySQL compensated标记完成。最后一步失败可安全重试而不二次加库存。
     */
    public void complete(SeckillOrderTask task) {
        // 数据库确认成功，同步redis成功状态
        // 对resultKey = "seckill:result:" .. orderId作状态转移，由pending切换到succeed
        if (task.succeeded()) {
            redis.execute(SUCCESS, List.of(RESULT_PREFIX + task.orderId()), Long.toString(task.userId()),
                    Long.toString(task.voucherId()), Long.toString(terminalTtl));
        } else if (!"FAILED".equals(task.state())) {
            // 既没有成功也没有失败，说明数据库处理订单事务报错回滚，需要重试，此处throw exception由外层的processRecords捕获并重试
            throw new IllegalStateException("Cannot compensate an order without a FAILED fence");
        } else if (!task.compensated()) {
            // 确定订单处理Failed，执行补偿脚本，释放预占
            Long result = redis.execute(COMPENSATE,
                    List.of(RESULT_PREFIX + task.orderId(),
                            "seckill:stock:" + task.voucherId(),
                            "seckill:order:" + task.voucherId(),
                            "seckill:reservation:" + task.voucherId(),
                            "seckill:compensated:" + task.voucherId()),
                    Long.toString(task.orderId()),
                    Long.toString(task.userId()),
                    Long.toString(task.voucherId()),
                    task.existingOrderId() == null ? "" : task.existingOrderId().toString(), Long.toString(terminalTtl));
            if (!Long.valueOf(1).equals(result)) {
                throw new IllegalStateException("Unsafe to release reservation: " + task.orderId());
            }
            // Redis收据已经写入。这里失败后重试会先命中幂等收据，不会再次增加Redis库存。
            tasks.markCompensated(task.orderId());
        }
    }

    /**
     * 先查MySQL订单，再查持久化任务，并核对userId，防止读取他人申请。
     * FAILED任务未释放时对外为COMPENSATING；订单存在则创建成功。数据库异常只降级处理中信息，不发布猜测的失败终态。
     */
    public Result query(long orderId, long userId) {
        try {
            VoucherOrder saved = orders.selectById(orderId);
            if (saved != null) {
                return saved.getUserId() == userId ? response(orderId, saved.getVoucherId(), OrderCreationState.SUCCEEDED)
                        : Result.fail("订单不存在或无权访问");
            }
            SeckillOrderTask task = tasks.find(orderId);
            if (task != null) {
                if (task.userId() != userId) {
                    return Result.fail("订单不存在或无权访问");
                }
                // 数据库FAILED只代表不再创建；尚未安全释放时外部状态必须是COMPENSATING。
                OrderCreationState state = task.succeeded() ? OrderCreationState.SUCCEEDED
                        : "FAILED".equals(task.state()) ? (task.compensated() ? OrderCreationState.FAILED : OrderCreationState.COMPENSATING)
                        : OrderCreationState.PENDING;
                if (state == OrderCreationState.FAILED && "ALREADY_PURCHASED".equals(task.reason())) {
                    return Result.ok(new OrderCreationResult(Long.toString(orderId), Long.toString(task.voucherId()), state,
                            "已购买该优惠券，此次申请的额外预占已释放"));
                }
                return response(orderId, task.voucherId(), state);
            }
        } catch (RuntimeException e) {
            log.warn("订单状态数据库查询暂不可用，orderId={}", orderId);
            // Never infer a terminal failure from a database timeout or a stale Redis result.
            return redisPending(orderId, userId, true);
        }
        return redisPending(orderId, userId, false);
    }

    /**
     * 数据库无结果或暂不可用时读取Redis受理Hash，并检查归属。
     * 若数据库不可用且Redis显示终态，返回暂不可查而不据此确认；Redis本身失败仍会向上抛异常。
     */
    private Result redisPending(long orderId, long userId, boolean databaseUnavailable) {
        Map<Object, Object> state = redis.opsForHash().entries(RESULT_PREFIX + orderId);
        if (state.isEmpty()) { return Result.fail(databaseUnavailable ? "状态暂不可查询，请稍后重试" : "订单不存在或无权访问"); }
        if (!Long.toString(userId).equals(state.get("userId"))) { return Result.fail("订单不存在或无权访问"); }
        OrderCreationState status = OrderCreationState.valueOf(state.get("state").toString());
        if (databaseUnavailable && (status == OrderCreationState.SUCCEEDED || status == OrderCreationState.FAILED)) {
            return Result.fail("状态暂不可查询，请稍后重试");
        }
        return response(orderId, Long.parseLong(state.get("voucherId").toString()), status);
    }

    /**
     * 把创建状态转换成给前端的业务说明；ID始终转字符串，SUCCEEDED文案指创建成功。
     */
    private static Result response(long id, long voucherId, OrderCreationState state) {
        String message = switch (state) {
            case PENDING -> "申请已受理，正在创建订单";
            case RETRYING -> "订单正在重试，请稍后查询";
            case SUCCEEDED -> "订单创建成功";
            case COMPENSATING -> "订单正在核对并释放预占，请稍后查询";
            case FAILED -> "订单创建失败，预占已释放";
        };
        return Result.ok(new OrderCreationResult(Long.toString(id), Long.toString(voucherId), state, message));
    }
}
