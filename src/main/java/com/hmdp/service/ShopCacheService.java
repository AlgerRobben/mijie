package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.cache.*;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 商铺缓存入口：固定名单分流，普通只Redis，热点才使用常驻Caffeine。
 */
@Slf4j
@Service
public class ShopCacheService {
    private final ShopCacheSource source;
    private final ShopRedisStore store;
    private final ShopCacheRepository repository;
    private final NormalShopCacheService normal;
    private final HotShopCacheService hot;
    private final ShopCacheCatalog catalog;
    private final ShopCacheChangeCoordinator changes;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor changeExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
        Thread t = new Thread(task, "shop-commit-cache-change");
        t.setDaemon(true);
        return t;
    });

    public ShopCacheService(ShopCacheSource source, ShopRedisStore store, ShopCacheRepository repository,
                            NormalShopCacheService normal, HotShopCacheService hot, ShopCacheCatalog catalog,
                            ShopCacheChangeCoordinator changes, ObjectMapper mapper) {
        this.source = source;
        this.store = store;
        this.repository = repository;
        this.normal = normal;
        this.hot = hot;
        this.catalog = catalog;
        this.changes = changes;
        this.mapper = mapper;
    }

    public Shop get(Long id) {
        try {
            return shop(view(id).snapshot());
        } catch (CacheBusyException e) {
            throw e;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new CacheBusyException("Cache dependency unavailable", e);
        }
    }

    public ShopCacheView view(long id) {
        if (id < 1) throw new IllegalArgumentException("Invalid shop ID");
        ShopCacheView cached = hot.cached(id); //读取caffeine中的hot快照
        if (cached != null) return cached; // 到期也返回旧值，获CAS标记的请求提交异步刷新。
        // 读redis元数据
        ShopCacheVersion version = store.version(id);
        if (version == null) return wrap(source.await(id)); // 元数据缺失也合并同ID请求。
        if (catalog.mode(version) == CacheMode.HOT) return hot.get(id, version);
        return wrap(normal.get(id, version)); // 删除后再插入可能在回源时变为HOT，以最终快照为准。
    }

    /**
     * 边缘已在后台回调，可受控等待源刷新；不得被Caffeine命中短路源缺失/过期恢复。
     */
    public ShopCacheView freshView(long id) {
        return wrap(source.await(id));
    }

    private ShopCacheView wrap(ShopCacheSnapshot snapshot) {
        ShopCacheView view = hot.view(snapshot);
        if (snapshot.cacheableHot()) hot.install(view); // 普通正文和空值均不进入上层。
        return view;
    }

    public List<String> activeHotIds() {
        return catalog.hotIds().stream().filter(id -> !repository.version(id).deleted())
                .map(Object::toString).toList();
    }

    public Shop shop(ShopCacheSnapshot snapshot) {
        if (snapshot.missing()) return null;
        try {
            return mapper.readValue(snapshot.dataJson(), Shop.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cache data is not a Shop", e);
        }
    }

    public void evict(Long id) {
        try {
            store.observe(repository.version(id)); // 提交后先提高版本，阻止在途旧回填。
            changeExecutor.execute(() -> {
                try {
                    changes.changed(id);
                } catch (Exception e) {
                    log.error("SHOP_CACHE_INVALIDATION_FAILED shopId={}，由CDC或查询恢复", id, e);
                }
            });
        } catch (Exception e) {
            log.error("SHOP_CACHE_INVALIDATION_FAILED shopId={}", id, e);
        }
    }

    public void evictReliably(Long id) {
        changes.changed(id);
    }

    public void invalidateLocal(Long id) {
        hot.markExpired(id);
    }

    public String invalidationChannel() {
        return store.channel();
    }

    public void notification(String body) {
        try {
            var event = mapper.readTree(body);
            hot.notification(Long.parseLong(event.path("id").asText()), event.path("revision").asText(),
                    CacheMode.valueOf(event.path("mode").asText()), event.path("deleted").asBoolean());
        } catch (Exception e) {
            log.warn("SHOP_CACHE_NOTIFICATION_INVALID", e);
        }
    }

    public Map<String, Object> statistics() {
        return Map.of("hot", hot.statistics(), "sourceDatabaseLoads", source.databaseLoads(), "sourceInFlight", source.inFlight());
    }

    @PreDestroy
    public void stop() {
        changeExecutor.shutdownNow();
    }
}
