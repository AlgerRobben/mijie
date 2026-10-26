package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 根据Spring RedisProperties创建同库同认证的Redisson客户端，供缓存Pub/Sub等功能使用。连接目标随测试Redis数据库覆盖而变化。
 */
@Configuration
public class RedissonConfig {
    /**
     * 从Spring属性取host/port/password/database，确保Redisson和StringRedisTemplate指向同一逻辑库。
     */
    @Bean
    public RedissonClient redissonClient(RedisProperties redisProperties){
        //设置配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setPassword(redisProperties.getPassword())
                .setDatabase(redisProperties.getDatabase());
        //返回client对象
        return Redisson.create(config);
    }
}
