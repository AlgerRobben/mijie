package com.hmdp.cache;

/**
 * Redis主快照。dataJson保留Jackson输出，Lua不解码商铺字段，避免大整数经过Lua浮点数丢精度。
 * id/revision也用字符串；逻辑时间是Unix毫秒，不解析LocalDateTime来判断过期。
 */
public record ShopCacheSnapshot(String id, CacheMode mode, String revision,
                                long logicalExpireAtEpochMillis, boolean missing, String dataJson) {
    public boolean expired(long now) {
        return mode == CacheMode.HOT && logicalExpireAtEpochMillis <= now;
    }

    public boolean cacheableHot() {
        return mode == CacheMode.HOT && !missing;
    }
}
