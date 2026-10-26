package com.hmdp;

import com.hmdp.dto.*;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.repository.SeckillOrderTaskRepository;
import com.hmdp.service.*;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;

import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实MySQL/Redis订单生命周期验证，测试主动调用消费/补偿路径以控制场景。覆盖重复消息、失败退款、行锁互斥、数据库不确定与归属检查。
 */
@SpringBootTest(properties = {"spring.redis.database=15", "hmdp.seckill.stream-consumer.enabled=false", "hmdp.canal.enabled=false"})
class OrderLifecycleIntegrationTest {
    private static final String PREFIX = "test:order-lifecycle:" + UUID.randomUUID();
    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("hmdp.seckill.stream", () -> PREFIX + ":orders");
        registry.add("hmdp.seckill.dead-letter-stream", () -> PREFIX + ":dlq");
        registry.add("hmdp.seckill.compensation-archive", () -> PREFIX + ":archive");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VoucherOrderServiceImpl service;
    @SpyBean OrderPersistenceService persistence;
    @SpyBean OrderResultService results;
    @SpyBean SeckillOrderTaskRepository tasks;
    long voucherId, userId;
    List<Long> accepted = new ArrayList<>();
    String consumer;
    boolean groupCreated;

    @BeforeEach
    void fixture() {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type) VALUES (1,?,1,1,1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "order-lifecycle-test-" + UUID.randomUUID()); return statement;
        }, key);
        voucherId = Objects.requireNonNull(key.getKey()).longValue();
        userId = 7_000_000_000_000L + Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,2,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)", voucherId);
        long now = System.currentTimeMillis();
        redis.opsForValue().multiSet(Map.of(stock(), "2", "seckill:begin:" + voucherId, Long.toString(now - 3600000),
                "seckill:end:" + voucherId, Long.toString(now + 3600000)));
        UserDTO user = new UserDTO(); user.setId(userId); UserHolder.saveUser(user);
        consumer = Objects.requireNonNull(ReflectionTestUtils.getField(service, "consumerName")).toString();
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        if (voucherId > 0) {
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucherId);
            jdbc.update("DELETE FROM tb_seckill_order_task WHERE voucher_id=?", voucherId);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucherId);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucherId);
            List<String> keys = new ArrayList<>(List.of(stock(), "seckill:begin:" + voucherId, "seckill:end:" + voucherId,
                    buyers(), owners(), "seckill:compensated:" + voucherId, PREFIX + ":orders", PREFIX + ":dlq", PREFIX + ":archive"));
            accepted.forEach(id -> keys.add(OrderResultService.RESULT_PREFIX + id));
            redis.delete(keys);
        }
    }

    @Test
    void successIsQueryableOnlyByOwnerAndRedeliveryDoesNotDeductAgain() {
        long id = accept();
        assertState(id, OrderCreationState.PENDING);
        var record = read(); process(record, 1); process(record, 2);
        assertState(id, OrderCreationState.SUCCEEDED);
        assertEquals(1, dbStock()); assertEquals("1", redis.opsForValue().get(stock()));
        assertEquals(1, orderCount()); assertEquals(0L, pending());
        assertFalse(results.query(id, userId + 1).success());
        redis.delete(OrderResultService.RESULT_PREFIX + id);
        assertState(id, OrderCreationState.SUCCEEDED);
    }

    @Test
    void transientWriteFailureRemainsRetryingThenCommits() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        long id = accept(); var record = read(); process(record, 1);
        assertState(id, OrderCreationState.RETRYING); assertEquals(1L, pending());
        assertEquals("1", redis.opsForValue().get(stock()));
        jdbc.update("UPDATE tb_seckill_voucher SET stock=2 WHERE voucher_id=?", voucherId);
        process(record, 2);
        assertState(id, OrderCreationState.SUCCEEDED); assertEquals(1, dbStock()); assertEquals(1, orderCount());
    }

    @Test
    void exhaustedOrderIsFencedRefundedOnceAndCannotBeCreatedByLateReplay() {
        redis.opsForValue().set(stock(), "1");
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        long id = accept(); var record = read(); process(record, 5);
        assertState(id, OrderCreationState.COMPENSATING); assertEquals("0", redis.opsForValue().get(stock()));
        assertEquals(1L, redis.opsForStream().size(PREFIX + ":dlq"));
        recover();
        assertState(id, OrderCreationState.FAILED); assertEquals(0, orderCount()); assertEquals(0, dbStock());
        assertEquals("1", redis.opsForValue().get(stock())); assertFalse(redis.opsForSet().isMember(buyers(), Long.toString(userId)));
        assertEquals(0L, redis.opsForStream().size(PREFIX + ":dlq"));
        assertEquals(1L, redis.opsForStream().size(PREFIX + ":archive"));
        SeckillOrderTask oldSnapshot = new SeckillOrderTask(id, userId, voucherId, "FAILED", "CREATION_FAILED", null, false);
        redis.delete(OrderResultService.RESULT_PREFIX + id);
        results.complete(oldSnapshot); // The durable receipt protects even after the Redis result expires.
        assertEquals("1", redis.opsForValue().get(stock()));
        jdbc.update("UPDATE tb_seckill_voucher SET stock=1 WHERE voucher_id=?", voucherId);
        assertEquals("FAILED", persistence.persist(order(id)).state());
        assertEquals(0, orderCount()); assertEquals(1, dbStock());
        long replacement = accept();
        results.complete(oldSnapshot); // Must not release this user's newer reservation.
        assertEquals(Long.toString(replacement), redis.opsForHash().get(owners(), Long.toString(userId)));
        assertEquals("0", redis.opsForValue().get(stock()));
        process(read(), 1); assertState(replacement, OrderCreationState.SUCCEEDED);
    }

    @Test
    void commitBeforeRedisConfirmationFailureNeverRestoresSoldStock() {
        long id = accept(); var record = read();
        doThrow(new IllegalStateException("Redis status unavailable after commit")).doCallRealMethod().when(results).complete(any());
        process(record, 1);
        assertEquals(1, orderCount()); assertEquals(1, dbStock()); assertEquals(1L, pending());
        assertState(id, OrderCreationState.SUCCEEDED); // MySQL is authoritative even while Redis still says RETRYING.
        process(record, 2);
        assertEquals(1, orderCount()); assertEquals(1, dbStock()); assertEquals("1", redis.opsForValue().get(stock()));
        assertEquals(0L, pending());
    }

    @Test
    void databaseUncertaintyKeepsCompensationQueuedWithoutRefund() {
        redis.opsForValue().set(stock(), "1");
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        long id = accept(); process(read(), 5);
        doThrow(new IllegalStateException("MySQL unavailable")).doCallRealMethod().when(persistence).fenceFailure(any());
        recover();
        assertEquals("0", redis.opsForValue().get(stock())); assertEquals(1L, redis.opsForStream().size(PREFIX + ":dlq"));
        assertState(id, OrderCreationState.COMPENSATING);
        recover(); assertState(id, OrderCreationState.FAILED); assertEquals("1", redis.opsForValue().get(stock()));
    }

    @Test
    void duplicatePurchaseRefundsOnlyExtraReservationAndKeepsPurchasedFlag() {
        long existingId = accept(); process(read(), 1);
        redis.opsForSet().remove(buyers(), Long.toString(userId)); redis.opsForHash().delete(owners(), Long.toString(userId));
        long duplicateId = accept(); process(read(), 1);
        assertState(duplicateId, OrderCreationState.FAILED); assertState(existingId, OrderCreationState.SUCCEEDED);
        assertEquals(1, orderCount()); assertEquals(1, dbStock()); assertEquals("1", redis.opsForValue().get(stock()));
        assertTrue(redis.opsForSet().isMember(buyers(), Long.toString(userId)));
        assertEquals(Long.toString(existingId), redis.opsForHash().get(owners(), Long.toString(userId)));
    }

    @Test
    void compensationWaitsForInFlightTransactionThenObservesCommittedOrder() throws Exception {
        long id = accept();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            SeckillOrderTask task = (SeckillOrderTask) invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("test-order-creator")) {
                locked.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return task;
        }).when(tasks).lock(any());
        ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-order-creator"));
        ExecutorService compensator = Executors.newSingleThreadExecutor();
        try {
            Future<SeckillOrderTask> commit = writer.submit(() -> persistence.persist(order(id)));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<SeckillOrderTask> failure = compensator.submit(() -> persistence.fenceFailure(order(id)));
            Thread.sleep(200); assertFalse(failure.isDone(), "failure must wait for the creation transaction's row lock");
            release.countDown(); assertTrue(commit.get(10, TimeUnit.SECONDS).succeeded());
            SeckillOrderTask verified = failure.get(10, TimeUnit.SECONDS); assertTrue(verified.succeeded()); results.complete(verified);
            assertEquals(1, orderCount()); assertEquals(1, dbStock()); assertEquals("1", redis.opsForValue().get(stock()));
        } finally { release.countDown(); writer.shutdownNow(); compensator.shutdownNow(); }
    }

    long accept() {
        Result result = service.seckillVoucher(voucherId); assertTrue(result.success()); assertInstanceOf(String.class, result.data());
        long id = Long.parseLong(result.data().toString()); accepted.add(id); return id;
    }
    MapRecord<String, Object, Object> read() {
        if (!groupCreated) { redis.opsForStream().createGroup(PREFIX + ":orders", ReadOffset.from("0"), "g1"); groupCreated = true; }
        var records = redis.opsForStream().read(Consumer.from("g1", consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(PREFIX + ":orders", ReadOffset.lastConsumed()));
        assertNotNull(records); assertEquals(1, records.size()); return records.getFirst();
    }
    void process(MapRecord<String, Object, Object> record, long deliveries) {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        ReflectionTestUtils.invokeMethod(target, "processRecord", record, deliveries);
    }
    void recover() {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        ReflectionTestUtils.invokeMethod(target, "recoverDeadLetters");
    }
    VoucherOrder order(long id) { return new VoucherOrder().setId(id).setUserId(userId).setVoucherId(voucherId); }
    void assertState(long id, OrderCreationState state) { Result result = results.query(id, userId); assertTrue(result.success()); assertEquals(state, ((OrderCreationResult) result.data()).state()); }
    int dbStock() { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId); }
    int orderCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Integer.class, voucherId); }
    long pending() { return redis.opsForStream().pending(PREFIX + ":orders", "g1").getTotalPendingMessages(); }
    String stock() { return "seckill:stock:" + voucherId; }
    String buyers() { return "seckill:order:" + voucherId; }
    String owners() { return "seckill:reservation:" + voucherId; }
}
