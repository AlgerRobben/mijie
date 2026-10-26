package com.hmdp.cache;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.time.Duration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

/** 缓存专用短超时客户端；不修改订单/登录使用的默认Redis客户端，避免扩大改造影响。 */
@Component
public class ShopCacheClients {
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;
    private final RedissonClient locks;
    public ShopCacheClients(RedisProperties redis,ShopCacheProperties properties) {
        var endpoint=new RedisStandaloneConfiguration(redis.getHost(),redis.getPort());
        endpoint.setDatabase(redis.getDatabase());
        if(redis.getPassword()!=null)endpoint.setPassword(redis.getPassword());
        if(redis.getUsername()!=null)endpoint.setUsername(redis.getUsername());
        var client=LettuceClientConfiguration.builder().commandTimeout(properties.getRedisCommandTimeout())
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(600)).build()).build())
                .shutdownTimeout(Duration.ofMillis(100)).build();
        factory=new LettuceConnectionFactory(endpoint,client);factory.afterPropertiesSet();
        template=new StringRedisTemplate(factory);template.afterPropertiesSet();
        Config config=new Config().setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://"+redis.getHost()+":"+redis.getPort())
                .setPassword(redis.getPassword()).setDatabase(redis.getDatabase())
                .setConnectTimeout(600).setTimeout(600).setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        locks=Redisson.create(config);
    }
    public StringRedisTemplate template(){return template;}
    public RedissonClient locks(){return locks;}
    @PreDestroy public void stop(){locks.shutdown();factory.destroy();}
}
