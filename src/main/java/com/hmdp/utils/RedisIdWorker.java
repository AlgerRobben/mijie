package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Redis自增辅助的long业务ID生成器：相对秒时间部分左移32位，再拼接按业务/日期分组的序列。
 * 日期键用于分隔每日计数，但当前不自动设置过期。ID用于唯一标识；创建事实时间应读取业务字段，不能直接把ID当标准UTC时间戳。
 */
@Component
public class RedisIdWorker {
    //开始时间戳
    // 此处保留现有历史基准值；它不是常见示例的2022-01-01秒值，不能照搬教程日期解释。
    private static final long BEGIN_TIMESTAMP = 164099520L;
    //序列号的位数
    private static final int COUNT_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
         this.stringRedisTemplate = stringRedisTemplate;
     }

    /**
     * keyPrefix划分业务计数空间（订单使用order）。本地时间计算相对秒，高位左移COUNT_BITS，Redis INCR得到日内低位序列。
     * 当前时间转换按ZoneOffset.UTC解释LocalDateTime，基准常量保持原值；ID不要用作真实订单创建时间。
     */
    public long nextId(String keyPrefix){
        //1.生成时间戳
        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //2.利用Redis自增生成序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = stringRedisTemplate.opsForValue().increment("cnt:" + keyPrefix + ":" + date);
        //3.拼接时间戳和序列号并返回
        return timestamp << COUNT_BITS | count;
    }

}
