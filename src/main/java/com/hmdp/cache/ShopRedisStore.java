package com.hmdp.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 固定分类下的版本观察/源发布。v3命名空间隔离旧切换协议的缓存和通知。
 */
@Slf4j
@Component
public class ShopRedisStore {
    private static final DefaultRedisScript<Long> OBSERVE = script("observe-shop-version.lua");
    private static final DefaultRedisScript<Long> PUBLISH = script("publish-shop-cache.lua");
    private static final DefaultRedisScript<Long> INVALIDATE = script("invalidate-normal-shop.lua");
    private static final DefaultRedisScript<Long> RETIRE = script("retire-hot-shop.lua");
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ShopCacheProperties properties;
    private final ShopCacheCatalog catalog;
    private final String channel;

    @Autowired
    public ShopRedisStore(ShopCacheClients clients, ObjectMapper mapper, ShopCacheProperties properties, ShopCacheCatalog catalog,
                          @Value("${spring.redis.database:0}") int database) {
        this(clients.template(), mapper, properties, catalog, database);
    }

    ShopRedisStore(StringRedisTemplate redis, ObjectMapper mapper, ShopCacheProperties properties, ShopCacheCatalog catalog, int database) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.catalog = catalog;
        channel = "hmdp:cache:shop:v3:" + database;
    }

    @PostConstruct
    public void checkFixedCatalog() {
        // 仅启动检查，防两实例使用不同静态名单，向同一Redis库发布相互矛盾的分类。
        redis.opsForValue().setIfAbsent("cache:shop:v3:catalog", catalog.fingerprint());
        if (!catalog.fingerprint().equals(redis.opsForValue().get("cache:shop:v3:catalog")))
            throw new IllegalStateException("Fixed HOT configuration differs from this Redis cache namespace; stop all instances and clear the owned v3 cache namespace before changing deployment configuration");
    }

    private static DefaultRedisScript<Long> script(String name) {
        var result = new DefaultRedisScript<Long>();
        result.setLocation(new ClassPathResource("lua/" + name));
        result.setResultType(Long.class);
        return result;
    }

    public static String metaKey(long id) {
        return "cache:shop:v3:meta:" + id;
    }

    public static String dataKey(long id, CacheMode mode) {
        return "cache:shop:v3:" + mode.name().toLowerCase() + ":" + id;
    }

    public static String lockKey(long id) {
        return "lock:shop:rebuild:v3:" + id;
    }

    public String channel() {
        return channel;
    }

    // 更新redis元数据
    public void observe(ShopCacheVersion version) {
        // redis服务端的lua引擎执行名为OBSERVE的lua脚本
        // 随后第一个参数为key列表，此处只有一个元数据的key，例如cache:shop:v3:meta:10
        // 之后的若干参数分别代表shopID，版本号，存在状态，热点/普通分类等
        redis.execute(OBSERVE, List.of(metaKey(version.shopId())), Long.toString(version.shopId()), version.revision(),
                catalog.mode(version).name(), version.deleted() ? "1" : "0");
    }

    public ShopCacheVersion version(long id) {
        Map<Object, Object> value = redis.opsForHash().entries(metaKey(id));
        if (value.isEmpty()) return null;
        try {
            return new ShopCacheVersion(id, value.get("revision").toString(), "1".equals(value.get("deleted")));
        } catch (RuntimeException e) {
            log.warn("SHOP_CACHE_META_INVALID id={}", id);
            return null;
        }
    }

    public ShopCacheSnapshot read(ShopCacheVersion version) {
        CacheMode mode = catalog.mode(version);
        String raw = redis.opsForValue().get(dataKey(version.shopId(), mode));
        if (raw == null) return null;
        try {
            var snapshot = mapper.readValue(raw, ShopCacheSnapshot.class);
            if (snapshot.mode() != mode || !snapshot.id().equals(Long.toString(version.shopId()))) return null;
            if (!snapshot.missing() && (snapshot.dataJson() == null || !mapper.readTree(snapshot.dataJson()).isObject()))
                return null;
            // 读取保留旧版本正文，由调用方决定先响应还是严格重建；已知存在状态变化仍不复用。
            if (mode == CacheMode.NORMAL && snapshot.missing() != version.deleted()) return null;
            return snapshot;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("SHOP_CACHE_DATA_INVALID id={}", version.shopId());
            return null;
        }
    }

    public boolean publish(ShopCacheVersion version, ShopCacheSnapshot snapshot, String owner) {
        String encoded = json(snapshot);
        if (snapshot.mode() == CacheMode.HOT && encoded.getBytes(StandardCharsets.UTF_8).length > properties.getHot().getMaximumSnapshotBytes())
            throw new CacheBusyException("HOT snapshot exceeds configured capacity budget");
        String event = event(version, snapshot.mode());
        // 按固定名单决定是否广播；热点删除后的路由是NORMAL，不能只按snapshot.mode判断。
        String notifyLocal = catalog.isHot(version.shopId()) ? "1" : "0";
        Long accepted = redis.execute(PUBLISH, List.of(lockKey(version.shopId()), metaKey(version.shopId()), dataKey(version.shopId(), snapshot.mode())),
                owner, snapshot.revision(), snapshot.mode().name(), encoded, snapshot.missing() ? "120" : "1800", channel, event, notifyLocal);
        return Long.valueOf(1).equals(accepted);
    }

    private String event(ShopCacheVersion version, CacheMode mode) {
        return json(Map.of("id", Long.toString(version.shopId()), "mode", mode.name(), "revision", version.revision(), "deleted", version.deleted()));
    }

    public void invalidateNormal(ShopCacheVersion version) {
        redis.execute(INVALIDATE, List.of(dataKey(version.shopId(), CacheMode.NORMAL), metaKey(version.shopId())),
                version.revision(), channel, event(version, CacheMode.NORMAL), catalog.isHot(version.shopId()) ? "1" : "0");
    }

    public void deleteRetiredHot(ShopCacheVersion version) {
        redis.execute(RETIRE, List.of(metaKey(version.shopId()), dataKey(version.shopId(), CacheMode.HOT)), version.revision());
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cache serialization failed", e);
        }
    }

    public static int compareRevision(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
}
