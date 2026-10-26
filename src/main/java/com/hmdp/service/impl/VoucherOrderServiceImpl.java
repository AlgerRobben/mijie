package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderTask;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.OrderPersistenceService;
import com.hmdp.service.OrderResultService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 秒杀异步订单编排入口。HTTP线程只执行Redis库存/购买资格检查、预占、状态登记和XADD，返回受理ID。
 * 后台单消费者读取Stream，通过OrderPersistenceService事务落库，再更新Redis结果并ACK；失败保留pending，超过上限转工作DLQ。
 * 独立补偿线程核对数据库、持久化FAILED围栏、幂等释放Redis预占并归档。它与消费线程通过同一MySQL任务行锁互斥。
 * 此链路使用Redis Stream，Canal缓存同步使用RocketMQ，两者的消息队列和死信机制不同。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Value("${hmdp.seckill.stream:stream.orders}")
    private String stream;
    @Value("${hmdp.seckill.dead-letter-stream:stream.orders.dlq}")
    private String deadLetterStream;
    @Value("${hmdp.seckill.compensation-archive:stream.orders.compensated}")
    private String compensationArchive;
    @Value("${hmdp.seckill.consumer-group:g1}")
    private String group;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> ARCHIVE_SCRIPT = new DefaultRedisScript<>();
    private static final int PENDING_SCAN_LIMIT = 100;
    private static final int PENDING_BATCH_SIZE = 20;

    static {
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill2.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        DEAD_LETTER_SCRIPT.setLocation(new ClassPathResource("lua/dead-letter-order.lua"));
        DEAD_LETTER_SCRIPT.setResultType(Long.class);
        ARCHIVE_SCRIPT.setLocation(new ClassPathResource("lua/archive-compensated-order.lua"));
        ARCHIVE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private OrderPersistenceService persistence;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private OrderResultService orderResults;
    @Value("${hmdp.seckill.stream-consumer.enabled:true}")
    private boolean streamConsumerEnabled;
    @Value("${hmdp.seckill.stream-consumer.claim-idle:30s}")
    private Duration claimIdle;
    @Value("${hmdp.seckill.stream-consumer.recovery-interval:5s}")
    private Duration recoveryInterval;
    @Value("${hmdp.seckill.stream-consumer.max-deliveries:5}")
    private int maxDeliveries;

    // 每次实例启动采用独立消费者名，旧实例的pending可被新实例认领，不能在所有JVM固定写c1。
    private final String consumerName = "order-" + UUID.randomUUID();
    private String pendingCursor = "0-0";
    private String compensationCursor = "0-0";
    private final ScheduledExecutorService compensationWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "order-compensation"); thread.setDaemon(true); return thread;
    });

    private final ExecutorService orderConsumer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voucher-order-consumer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Bean初始化后创建/复用消费组，启动订单线程与独立补偿扫描。
     * enabled=false用于只查询/接收请求的实例或隔离测试。
     */
    @PostConstruct
    public void startConsumer() {
        if (!streamConsumerEnabled) {
            return;
        }
        // 创建流与消费者组
        ensureConsumerGroup();
        // 向后台线程提交消费订单的任务
        orderConsumer.submit(this::consumeOrders);
        // 每隔固定时间执行recoverDeadLettersSafely方法
        compensationWorker.scheduleWithFixedDelay(this::recoverDeadLettersSafely, 0,
                Math.max(100, recoveryInterval.toMillis()), TimeUnit.MILLISECONDS);
    }

    /**
     * 退出时中断两个后台执行器；在途消息如果未ACK仍留在pending供新实例恢复。
     */
    @PreDestroy
    public void stopConsumer() {
        orderConsumer.shutdownNow();
        compensationWorker.shutdownNow();
    }

    /**
     * 从流起点创建消费组，MKSTREAM允许尚无消息时创建空流；已存在组的BUSYGROUP可以忽略，其他连接/权限错误必须暴露。
     */
    private void ensureConsumerGroup() {
        try {
            // XGROUP CREATE stream.orders g1 0 MKSTREAM
            // 为 stream.orders 创建名为 g1 的消费组，将初始消费位置设在流的起点
            // 流不存在时，顺便创建空流。
            stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(bytes(stream), group, ReadOffset.from("0"), true));
        } catch (DataAccessException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    /**
     * 把流名/组命令参数转UTF-8，用于底层Redis连接API。
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 单线程循环：先按间隔扫描遗留pending，再XREADGROUP读取尚未投递的新消息。
     * 每次最多1条，阻塞最长2秒；空闲时不忙轮询，消费异常后退避1秒。周期控制用nanoTime避免墙上时钟跳变。
     */
    private void consumeOrders() {
        long nextRecoveryAt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 用循环revovertime+interval的方式实现类似定时任务的形式
                // 此处可以开启独立后台线程做成定时任务
                // 当前把它放进消费循环，让本实例的新订单和 pending 重试串行处理，是一种简化线程协作的选择
                if (System.nanoTime() >= nextRecoveryAt) {
                    recoverPendingOrders();
                    nextRecoveryAt = System.nanoTime() + recoveryInterval.toNanos();
                }
                //stream.orders
                //├─ 消息正文
                //│    1000-0 → userId=42、voucherId=10、id=90001
                //│    2000-0 → ...
                //│
                //└─ 消费组 g1
                //     ├─ 组级 pending：记录本组所有未确认消息及其归属
                //     ├─ 消费者 A 的 pending 索引
                //     └─ 消费者 B 的 pending 索引
                // 本Java实例对应的消费者通过消费者组从stream.orders中读取消息
                // 读取到的消息登记到消费者组的pending列表中，ACK后移除，未ACK留在pending列表中的消息等当前归属的消费者重试
                // 消息长时间空闲可被其他消费者认领
                // 重试达上限后转入DLQ，原消息ACK
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty()
                                .count(1) // 一次最多读取一条消息
                                .block(Duration.ofSeconds(2)), // 没消息是最多阻塞等待2s，此时redis登记客户端等待状态后，继续处理其他事件
                        // 消费组的lastConsumed读取未分配的新消息；本组已经投递但未ACK的消息在pending扫描中处理。
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record, 1);
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("消费优惠券订单失败，稍后重试", e);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 定期检查消费组中已经投递、但尚未 ACK 的订单消息，
     * 无论归属，认领空闲时间足够长的消息，再次执行 processRecord()。
     * 它既恢复处理失败的消息，也接管旧实例宕机后留下的消息。
     */
    private void recoverPendingOrders() {
        int scanned = 0;
        // scanned < PENDING_SCAN_LIMIT 限制单词重试任务的扫描工作量
        while (scanned < PENDING_SCAN_LIMIT && !Thread.currentThread().isInterrupted()) {
            // 确定扫描范围以及分页参数等等
            boolean hasCursor = !"0-0".equals(pendingCursor);
            Range<String> range = hasCursor
                    ? Range.rightUnbounded(Range.Bound.inclusive(pendingCursor)) : Range.unbounded();
            int pageSize = Math.min(PENDING_BATCH_SIZE, PENDING_SCAN_LIMIT - scanned);
            // 分页取pending消息
            PendingMessages pending = stringRedisTemplate.opsForStream()
                    .pending(stream, group, range, pageSize + (hasCursor ? 1 : 0));
            if (pending.isEmpty()) {
                pendingCursor = "0-0";
                return;
            }
            int examined = 0;
            // 逐条处理pending消息
            for (PendingMessage message : pending) {
                if (hasCursor && pendingCursor.equals(message.getIdAsString())) {
                    continue;
                }
                pendingCursor = message.getIdAsString();
                scanned++;
                examined++;
                // 判断距离上次投递的空闲时间
                if (message.getElapsedTimeSinceLastDelivery().compareTo(claimIdle) < 0) {
                    continue;
                }
                // 认领消息，重新执行processRecord()
                RedisStreamCommands.XClaimOptions options = RedisStreamCommands.XClaimOptions
                        .minIdle(claimIdle).ids(message.getId()).idle(claimIdle);
                List<MapRecord<String, Object, Object>> claimed = stringRedisTemplate.opsForStream()
                        .claim(stream, group, consumerName, options);
                for (MapRecord<String, Object, Object> record : claimed) {
                    processRecord(record, message.getTotalDeliveryCount() + 1);
                }
            }
            if (examined < pageSize) {
                pendingCursor = "0-0";
                return;
            }
        }
    }

    /**
     * 把Stream字段转换成订单，调用独立事务Bean；其返回意味着事务已结束，再更新Redis结果并ACK。
     * 异常时保留pending并尝试标记RETRYING；达到投递上限则原子转DLQ。
     * 数据库已提交但Redis确认失败不重新扣库存，后续重复消费读取持久化SUCCEEDED补状态/ACK。
     */
    private void processRecord(MapRecord<String, Object, Object> record, long deliveryCount) {
        // 声明订单变量
        VoucherOrder order = null;
        // record
        //  ├─ Stream名称：stream.orders
        //  ├─ 消息ID：1720000000000-0
        //  └─ 消息正文：
        //       userId    → "42"
        //       voucherId → "10"
        //       id        → "90001"
        try {
            // 取出消息正文，大致相当于：
            // {
            //    "userId"    -> "42",
            //    "voucherId" -> "10",
            //    "id"        -> "90001"
            //}
            Map<Object, Object> values = record.getValue();
            // 将消息正文转成订单对象填入之前声明的order
            order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
            // 处理订单操作，主要动作包括MYSQL锁住创建任务、检查已有订单、条件扣减数据库库存、插入订单并更新任务结果
            SeckillOrderTask task = handleVoucherOrder(order);
            // 独立事务代理已返回并提交MySQL，随后写Redis结果，此时Redis失败只能重试确认，不能退还已售库存。
            orderResults.complete(task);
        } catch (Exception e) {
            // 若上述订单处理过程既没有处理成功，同步数据库和redis
            // 也没有处理失败，释放预占
            // 则抛出异常，捕获异常后准备重试
            log.error("订单处理失败，streamId={}，deliveryCount={}", record.getId(), deliveryCount, e);
            if (order != null && order.getId() != null) {
                try {
                    orderResults.retrying(order, deliveryCount);
                }
                catch (RuntimeException statusFailure) {
                    log.warn("订单重试状态暂不可更新，orderId={}", order.getId());
                }
            }
            // 达到重试上限后移入死信流
            // 死信流默认是 stream.orders.dlq，仍是一条普通 Redis Stream，由项目代码约定用来存放需要核对的工作项。
            if (deliveryCount >= maxDeliveries) {
                moveToDeadLetter(record, e);
            }
            // 重试分支跳过了下面的ACK，未ACK的消息等待恢复中
            return;
        }
        try {
            // ACK，正常情况下处理成功和处理失败都会走到这里ACK，重试则不会走到这里
            stringRedisTemplate.opsForStream().acknowledge(stream, group, record.getId());
        } catch (Exception e) {
            // 数据库事务已提交；保留 pending，下一次消费依靠订单幂等处理后重试 ACK。
            log.error("订单已处理但确认消息失败，streamId={}", record.getId(), e);
        }
    }

    /**
     * Lua先验证当前消费者仍拥有pending，再把原字段/错误/次数移入工作DLQ并ACK原流。
     * 这里转移的是待核对工作，不立即猜测失败并退库存；转移出错时保留原pending。
     */
    private void moveToDeadLetter(MapRecord<String, Object, Object> record, Exception failure) {
        // 保存异常信息和异常类型便于排查
        String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        try {
            // 调用lua脚本，传入待转移至死信流的订单stream消息id，即record.getId().getValue()
            Long moved = stringRedisTemplate.execute(DEAD_LETTER_SCRIPT,
                    List.of(stream, deadLetterStream),  // 原订单消息流与死信流
                    group, consumerName, // 消费组和消费者名
                    record.getId().getValue(), reason);
            // 脚本返回1表示转移成功
            if (Long.valueOf(1).equals(moved)) {
                log.error("ORDER_DEAD_LETTER streamId={}，已进入 {} 等待数据库核对和补偿",
                        record.getId(), deadLetterStream);
            } else {
                log.warn("订单未移入待处理队列，可能已被其他消费者接管，streamId={}", record.getId());
            }
        } catch (Exception e) {
            // 转移失败时不 ACK，下一轮仍可从 pending 恢复。
            log.error("订单转入待处理队列失败，streamId={}", record.getId(), e);
        }
    }

    /**
     * 补偿定时任务外层捕获异常，避免一次依赖错误让后续定时扫描永久停止。
     */
    private void recoverDeadLettersSafely() {
        try {
            recoverDeadLetters();
        }
        catch (Exception e) {
            log.error("ORDER_COMPENSATION_RETRY 补偿扫描暂不可用，保留工作项等待恢复", e);
        }
    }

    /**
     * 扫描工作DLQ，使用与创建相同的MySQL任务锁核对：已提交则补成功，未提交则先提交FAILED围栏。
     * 事务返回后幂等释放Redis预占，成功才写归档并移除工作项；数据库不可用或归属不明继续保留。
     */
    private void recoverDeadLetters() {
        // 根据游标确定本次从哪里扫描
        // compensationCursor 保存上次扫描到的死信消息 ID：
        // - 为 "0-0" 时，从死信流头部开始
        // - 否则从该消息 ID 开始，向后扫描
        Range<String> range = "0-0".equals(compensationCursor) ? Range.unbounded()
                : Range.rightUnbounded(Range.Bound.inclusive(compensationCursor));
        // 用 XRANGE 从游标处向后读取一批消息，最多读21条
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                .range(deadLetterStream, range,
                        org.springframework.data.redis.connection.RedisZSetCommands.Limit.limit().count(21));
        if (records == null || records.isEmpty()) {
            compensationCursor = "0-0";
            return;
        }
        int processed = 0;
        // 跳过已读边界
        for (MapRecord<String, Object, Object> record : records) {
            if (record.getId().getValue().equals(compensationCursor)) {
                continue;
            }
            compensationCursor = record.getId().getValue();
            // 本轮尝试处理的条数++
            processed++;
            try {
                // 消息信息填入order对象
                VoucherOrder order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                // 根据数据库中订单表中的记录确认订单处理任务的状态
                SeckillOrderTask task = persistence.fenceFailure(order);
                // 根据task已提交的处理状态结单
                // 成功则更新redis中resultkey.state
                // 失败释放预占库存
                // 抛出错误则死信流中保留该消息留待下次定时任务扫描重复处理
                orderResults.complete(task);
                // 调用archive-compensated-order.lua脚本
                // 把已处理完成的死信工作项保存到归档流，再从死信流中删除
                stringRedisTemplate.execute(ARCHIVE_SCRIPT, List.of(deadLetterStream, compensationArchive),
                        record.getId().getValue(), task.succeeded() ? "ORDER_ALREADY_COMMITTED" : "RESERVATION_RELEASED");
                log.info("ORDER_COMPENSATED orderId={} resolution={}", order.getId(), task.succeeded() ? "SUCCEEDED" : "FAILED");
            } catch (Exception e) {
                // 死信流未使用消费组，无ACK机制，处理报错后不会被XDEL指令删除，继续留在死信流中等待定时任务重复扫描处理
                // 目前无重复处理上限
                // 最终必要时人工介入处理
                log.error("ORDER_COMPENSATION_RETRY deadLetterId={}，数据库结果未知或预占无法安全释放，保留工作项",
                        record.getId(), e);
            }
        }
        if (processed < 20) {
            compensationCursor = "0-0";
        }
    }

    /**
     * HTTP秒杀受理入口，用户身份由登录拦截器提供。生成业务ID后，Lua原子检查时间/库存/一人一券并预占、写Stream。
     * 返回的字符串ID仅表示Redis已受理，不等于MySQL订单成功，也避免浏览器大整数精度丢失。最终结果通过状态接口查询。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券不存在");
        }
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, List.of(),
                voucherId.toString(), userId.toString(), Long.toString(orderId), stream);
        if (result == null) {
            return Result.fail("秒杀服务暂不可用");
        }
        return switch (result.intValue()) {
            // 成功响应是受理ID；MySQL可能尚未落库，前端不能据此直接展示最终抢购成功。
            case 0 -> Result.ok(Long.toString(orderId));
            case 1 -> Result.fail("库存不足");
            case 2 -> Result.fail("不可重复下单");
            case 3 -> Result.fail("优惠券库存尚未初始化");
            case 4 -> Result.fail("秒杀尚未开始");
            case 5 -> Result.fail("秒杀已结束");
            default -> Result.fail("秒杀服务暂不可用");
        };
    }

    /**
     * 转交OrderPersistenceService的Spring事务代理，避免同类内部直接调用使@Transactional失效。
     */
    @Override
    public SeckillOrderTask handleVoucherOrder(VoucherOrder order) {
        return persistence.persist(order);
    }

    /**
     * 核对ID基本格式，再携当前登录用户查询创建状态；归属验证由OrderResultService执行。
     */
    @Override
    public Result queryOrderStatus(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单不存在或无权访问");
        }
        return orderResults.query(orderId, UserHolder.getUser().getId());
    }
}
