package com.hmdp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 读取层允许信息旧版本，存在状态相反的快照仍不能复用。严格版本判断保留在源工作中。
 */
class ShopRedisStoreReadTest {
    private ShopRedisStore store(StringRedisTemplate redis) {
        var props = new ShopCacheProperties();
        return new ShopRedisStore(redis, new ObjectMapper(), props, new ShopCacheCatalog(props), 15);
    }

    private String json(boolean missing, String id) throws Exception {
        return new ObjectMapper().writeValueAsString(new ShopCacheSnapshot(id, CacheMode.NORMAL, "8", 0, missing,
                missing ? null : "{\"id\":1,\"name\":\"old\"}"));
    }

    @Test
    void oldNormalVersionRemainsReadableForStaleFirstResponse() throws Exception {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(ShopRedisStore.dataKey(1, CacheMode.NORMAL))).thenReturn(json(false, "1"));
        assertEquals("8", store(redis).read(new ShopCacheVersion(1, "9", false)).revision());
    }

    @Test
    void knownDeletionDoesNotReturnOldPositive() throws Exception {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(ShopRedisStore.dataKey(1, CacheMode.NORMAL))).thenReturn(json(false, "1"));
        assertNull(store(redis).read(new ShopCacheVersion(1, "9", true)));
    }

    @Test
    void knownReinsertionDoesNotReturnOldMissingSentinel() throws Exception {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(ShopRedisStore.dataKey(1, CacheMode.NORMAL))).thenReturn(json(true, "1"));
        assertNull(store(redis).read(new ShopCacheVersion(1, "9", false)));
    }

    @Test
    void wrongShopIdIsStillRejected() throws Exception {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(ShopRedisStore.dataKey(1, CacheMode.NORMAL))).thenReturn(json(false, "2"));
        assertNull(store(redis).read(new ShopCacheVersion(1, "9", false)));
    }
}
