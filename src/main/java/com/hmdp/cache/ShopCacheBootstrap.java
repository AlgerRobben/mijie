package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 一次启动预热：固定HOT名单读源/填本地；普通商铺只同步轻量版本元数据。
 */
@Slf4j
@Component
public class ShopCacheBootstrap {
    private final ShopCacheRepository repository;
    private final ShopRedisStore store;
    private final ShopCacheSource source;
    private final HotShopCacheService hot;
    private final ShopCacheCatalog catalog;

    public ShopCacheBootstrap(ShopCacheRepository repository, ShopRedisStore store, ShopCacheSource source, HotShopCacheService hot, ShopCacheCatalog catalog) {
        this.repository = repository;
        this.store = store;
        this.source = source;
        this.hot = hot;
        this.catalog = catalog;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        repository.versions().forEach(store::observe);
        int count = 0;
        for (long id : catalog.hotIds()) {
            var version = repository.version(id);
            if (version.deleted()) {
                log.warn("SHOP_HOT_BOOTSTRAP_MISSING id={}", id);
                continue;
            }
            var snapshot = source.await(id);
            hot.install(hot.view(snapshot));
            count++;
        }
        log.info("SHOP_HOT_BOOTSTRAP_READY count={} fixedIds={}，无物理TTL", count, catalog.hotIds());
    }
}
