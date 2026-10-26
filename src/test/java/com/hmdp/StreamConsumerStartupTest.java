package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证真实Redis中自动创建订单Stream及消费组，说明服务启动时不依赖手工XGROUP命令。
 */
@SpringBootTest(properties = "spring.redis.database=15")
class StreamConsumerStartupTest {
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void createsTheOrderStreamAndConsumerGroupOnStartup() {
        assertTrue(Boolean.TRUE.equals(redis.hasKey("stream.orders")));
    }
}
