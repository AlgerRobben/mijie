package com.hmdp.config;

import com.hmdp.service.ShopCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.redisson.api.RedissonClient;
import org.redisson.api.RTopic;
import org.redisson.client.codec.StringCodec;

/**
 * 订阅源发布通知：HOT保留旧值并标记异步刷新，NORMAL/删除移除本地副本。
 * Redis Pub/Sub不持久化；频道名包含逻辑库编号，因为Pub/Sub频道本身不按Redis数据库隔离。
 * 本地5秒逻辑期限兜底漏通知；Redis源的30分钟逻辑期限仍决定源数据的最终恢复窗口。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "hmdp.cache", name = "broadcast-enabled", havingValue = "true", matchIfMissing = true)
public class ShopCacheBroadcastConfig {
    /**
     * StringCodec直接读取JSON事件字符串，ID/revision本身按字符串编码，避免大整数损失精度。
     */
    @Bean(initMethod = "subscribe", destroyMethod = "close")
    public LocalInvalidationSubscription shopCacheInvalidationListener(RedissonClient redis, ShopCacheService cache) {
        return new LocalInvalidationSubscription(redis.getTopic(cache.invalidationChannel(), StringCodec.INSTANCE), cache);
    }

    public static class LocalInvalidationSubscription implements AutoCloseable {
        private final RTopic topic;
        private final ShopCacheService cache;
        private int listenerId;

        LocalInvalidationSubscription(RTopic topic, ShopCacheService cache) {
            this.topic = topic;
            this.cache = cache;
        }

        /**
         * 注册频道监听；消息版本落后时忽略，更新本机副本不再广播，避免通知循环。
         */
        public void subscribe() {
            listenerId = topic.addListener(String.class, (channel, body) -> {
                try {
                    cache.notification(body);
                } catch (RuntimeException e) {
                    log.warn("忽略无效的本地缓存失效通知");
                }
            });
        }

        /**
         * 关闭时移除该监听器，避免重启后遗留重复回调。
         */
        @Override
        public void close() {
            topic.removeListener(listenerId);
        }
    }
}
