package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 旧版教程缓存包装，保留给通用CacheClient及历史日期解析诊断。
 * 当前商铺主链路使用cache.ShopCacheSnapshot/Jackson，过期判断只用毫秒时间戳，不再解析此日期。
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Long expireAtEpochMillis;
    private Object data;
}
