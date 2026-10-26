package com.hmdp.cache;

import org.springframework.stereotype.Service;

/**
 * 提交/CDC统一入口：固定NORMAL失效，固定HOT立即重建；删除清理正值，重新插入仍按固定名单处理。
 */
@Service
public class ShopCacheChangeCoordinator {
    private final ShopCacheRepository repository;
    private final ShopRedisStore store;
    private final ShopCacheSource source;
    private final HotShopCacheService hot;
    private final ShopCacheCatalog catalog;
    private final ShopCacheNotifier notifier;

    public ShopCacheChangeCoordinator(ShopCacheRepository repository, ShopRedisStore store, ShopCacheSource source, HotShopCacheService hot, ShopCacheCatalog catalog, ShopCacheNotifier notifier) {
        this.repository = repository;
        this.store = store;
        this.source = source;
        this.hot = hot;
        this.catalog = catalog;
        this.notifier = notifier;
    }

    public void changed(long id) {
        // 回查数据库版本表获取当前最新版本
        // 缓存失效与重建的前提：确定旧缓存版本低于当前查到的最新版本，防止后来重新插入的新数据被抹除。
        var version = repository.version(id);
        store.observe(version);
        // 热点缓存的删除操作 + 普通缓存的增删改操作 = NORMAL
        if (catalog.mode(version) == CacheMode.NORMAL) {
            // 本地caffeine副本失效
            hot.retire(id, version.revision());
            // 清理redis过时普通缓存 + redis pub/sub广播其他JVM删除caffeine过时热点缓存
            store.invalidateNormal(version);
            // 清理redis过时热点缓存
            store.deleteRetiredHot(version);
        } else {
            // redis热点缓存回源重建 + 广播其他JVM更新本地caff
            var snapshot = source.await(id);
            // 本地caffeine副本缓存更新
            hot.install(hot.view(snapshot)); // 主副本发布成功才可让MQ处理返回成功。
        }
        notifier.edges(id); // 失败抛出，Broker重投时可以复用已经更新的源。
    }
}
