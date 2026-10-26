package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.redis.database=15",
        "hmdp.seckill.stream-consumer.enabled=false"
})
/**
 * 验证pending拥有者检查与DLQ原子转移只执行一次，使用隔离队列避免消费真实订单。
 */
class OrderDeadLetterScriptTest {
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void claimedOrderCanBeMovedToDeadLetterOnce() {
        String suffix = UUID.randomUUID().toString();
        String stream = "test:orders:" + suffix;
        String deadLetter = "test:orders:dlq:" + suffix;
        String group = "test-group";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/dead-letter-order.lua"));
        script.setResultType(Long.class);
        try {
            redis.opsForStream().add(stream, Map.of("id", "42", "userId", "7", "voucherId", "3"));
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            List<MapRecord<String, Object, Object>> read = redis.opsForStream().read(
                    Consumer.from(group, "old-instance"), StreamReadOptions.empty().count(1),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));
            assertNotNull(read);
            String id = read.get(0).getId().getValue();

            assertEquals(0L, redis.execute(script, List.of(stream, deadLetter), group, "new-instance", id, "failure"));
            assertEquals(1L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
            assertEquals(1, redis.opsForStream().pending(stream, group, Range.unbounded(), 20).size());
            // 当前 Redisson 适配层会把排他下界当成包含；消费者按包含游标并跳过首条分页。
            assertEquals(1, redis.opsForStream().pending(stream, group,
                    Range.rightUnbounded(Range.Bound.inclusive(id)), 20).size());

            var streamOperations = redis.opsForStream();
            var originalRecord = read.get(0);
            assertNotNull(originalRecord);
            var originalId = originalRecord.getId();
            assertNotNull(originalId);
            RedisStreamCommands.XClaimOptions tooEarly = RedisStreamCommands.XClaimOptions
                    .minIdle(java.time.Duration.ofSeconds(30)).ids(originalId)
                    .idle(java.time.Duration.ofSeconds(30));
            assertEquals(0, streamOperations.claim(stream, group, "new-instance", tooEarly).size());
            RedisStreamCommands.XClaimOptions options = RedisStreamCommands.XClaimOptions
                    .minIdle(java.time.Duration.ZERO).ids(originalId).idle(java.time.Duration.ZERO);
            List<MapRecord<String, Object, Object>> claimed = streamOperations.claim(stream, group,
                    "new-instance", options);
            assertNotNull(claimed);
            assertEquals(1, claimed.size());
            assertEquals(1L, redis.execute(script, List.of(stream, deadLetter), group, "new-instance", id, "failure"));
            assertEquals(0L, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
            assertEquals(1L, redis.opsForStream().size(deadLetter));
            assertEquals("42", redis.opsForStream().range(deadLetter, Range.unbounded())
                    .get(0).getValue().get("id"));
            assertEquals(0L, redis.execute(script, List.of(stream, deadLetter), group, "new-instance", id, "failure"));
            assertEquals(1L, redis.opsForStream().size(deadLetter));
        } finally {
            redis.delete(List.of(stream, deadLetter));
        }
    }
}
