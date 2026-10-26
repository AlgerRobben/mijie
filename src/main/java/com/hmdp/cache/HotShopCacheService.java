package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HOT常驻本地快照。5秒是对象内的逻辑期限，不配置expireAfterWrite，到期不删除旧值。
 * CAS任务标记允许请求线程注册、工作线程清理；不跨线程转交ReentrantLock/RLock。
 */
@Slf4j
@Service
public class HotShopCacheService {
    private final Cache<Long, ShopCacheView> local;
    private final ConcurrentHashMap<Long, Object> refreshing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> retryAfter = new ConcurrentHashMap<>();
    private final ShopCacheSource source;
    private final ShopRedisStore store;
    private final ShopCacheProperties properties;
    private final ShopCacheCatalog catalog;
    private final ThreadPoolExecutor executor;
    private final AtomicLong refreshes = new AtomicLong();

    public HotShopCacheService(ShopCacheSource source, ShopRedisStore store, ShopCacheProperties properties, ShopCacheCatalog catalog) {
        this.source = source;
        this.store = store;
        this.properties = properties;
        this.catalog = catalog;
        if (properties.getHot().getMaximumEntries() < properties.getHot().getMaximumShops())
            throw new IllegalArgumentException("HOT local capacity must cover the entire admitted list");
        local = Caffeine.newBuilder().maximumSize(properties.getHot().getMaximumEntries()).recordStats().build();
        int threads = properties.getReplicaConcurrency();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> {
                    Thread t = new Thread(task, "shop-local-refresh");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public ShopCacheView cached(long id) {
        ShopCacheView view = local.getIfPresent(id);
        if (view == null || !view.snapshot().cacheableHot()) return null;
        // 逻辑过期申请后台异步刷新
        if (view.localLogicalExpireAtEpochMillis() <= System.currentTimeMillis()) requestRefresh(id);
        return view; // 有旧值时不等待Redis/数据库读取完成。
    }

    public ShopCacheView get(long id, ShopCacheVersion version) {
        ShopCacheSnapshot snapshot = store.read(version);
        if (snapshot == null || version.deleted()) snapshot = source.await(id);
        ShopCacheView view = view(snapshot);
        if (ShopRedisStore.compareRevision(snapshot.revision(), version.revision()) < 0)
            view = new ShopCacheView(snapshot, 0);
        // 写回caffeine
        install(view);
        // 逻辑过期异步刷新
        if (snapshot.expired(System.currentTimeMillis()) ||
                ShopRedisStore.compareRevision(snapshot.revision(), version.revision()) < 0) requestRefresh(id);
        return view;
    }

    public ShopCacheView view(ShopCacheSnapshot snapshot) {
        long now = System.currentTimeMillis();
        long deadline = snapshot.mode() == CacheMode.HOT
                ? Math.min(now + 1000 * properties.getHot().getLocalLogicalTtlSeconds(), snapshot.logicalExpireAtEpochMillis()) : 0;
        return new ShopCacheView(snapshot, deadline);
    }

    public void install(ShopCacheView incoming) {
        ShopCacheSnapshot value = incoming.snapshot();
        long id = Long.parseLong(value.id());
        if (value.mode() != CacheMode.HOT || value.missing()) {
            retire(id, value.revision());
            return;
        }
        ShopCacheVersion current = store.version(id);
        boolean admitted = catalog.isHot(id) && current != null && !current.deleted();
        if (!admitted || ShopRedisStore.compareRevision(value.revision(), current.revision()) < 0) return;
        // 每个ID一个数据条目；不允许未知名单或普通流量挤占。启动时校验固定名单与容量。
        if (local.getIfPresent(id) == null && local.estimatedSize() >= properties.getHot().getMaximumShops())
            throw new CacheBusyException("HOT local capacity exceeded");
        local.asMap().compute(id, (key, old) -> {
            if (old != null) {
                int version = ShopRedisStore.compareRevision(value.revision(), old.snapshot().revision());
                if (version < 0) return old;
                if (version == 0 && value.logicalExpireAtEpochMillis() < old.snapshot().logicalExpireAtEpochMillis())
                    return old;
                if (version == 0 && value.logicalExpireAtEpochMillis() == old.snapshot().logicalExpireAtEpochMillis()
                        && incoming.localLogicalExpireAtEpochMillis() < old.localLogicalExpireAtEpochMillis())
                    return old;
            }
            return incoming; // 原子替换，不先invalidate再put制造空窗。
        });
    }

    public void markExpired(long id) {
        local.asMap().computeIfPresent(id, (key, old) -> new ShopCacheView(old.snapshot(), 0));
        requestRefresh(id);
    }

    public void requestRefresh(long id) {
        // retryAfter中存着缓存id和对应下次需要重试的时间
        // 此处没有实现主动失败重试，若要实现可以尝试使用定时任务线程池定时遍历retryAfter查找到期的键值对并执行即可
        // refreshes达到上限可停止重试
        if (retryAfter.getOrDefault(id, 0L) > System.currentTimeMillis()) return;
        Object marker = new Object();
        if (refreshing.putIfAbsent(id, marker) != null) return; // 同JVM同ID只有获胜请求提交任务。
        try {
            executor.execute(() -> {
                try {
                    ShopCacheVersion version = source.version(id);
                    if (!catalog.isHot(id) || version.deleted()) {
                        retire(id, version.revision());
                        return;
                    }
                    ShopCacheView latest = local.getIfPresent(id);
                    // 获标记后二次检查：迟到的旧快照请求不重复刷新刚刚更新的条目。
                    if (latest != null && latest.localLogicalExpireAtEpochMillis() > System.currentTimeMillis()
                            && ShopRedisStore.compareRevision(latest.snapshot().revision(), version.revision()) >= 0)
                        return;
                    // 统计热点缓存刷新尝试次数
                    refreshes.incrementAndGet();
                    ShopCacheSnapshot snapshot = store.read(version);
                    if (!source.usable(snapshot, version, System.currentTimeMillis())) snapshot = source.await(id);
                    install(view(snapshot));
                    retryAfter.remove(id);
                } catch (Exception e) {
                    // 异步刷新失败，retryAfter中记录一下重建key的id和下次重建时间
                    // 定时任务线程扫描retryAfter重试若干次即可
                    retryAfter.put(id, System.currentTimeMillis() + 500);
                    log.warn("SHOP_HOT_REPLICA_REFRESH_FAILED id={}，保留旧快照", id, e);
                } finally {
                    refreshing.remove(id, marker);
                }
            });
        } catch (RejectedExecutionException e) {
            refreshing.remove(id, marker);
            retryAfter.put(id, System.currentTimeMillis() + 100);
            log.warn("SHOP_HOT_REFRESH_REJECTED id={}，后续查询重试", id);
        }
    }

    public void notification(long id, String revision, CacheMode mode, boolean deleted) {
        ShopCacheView old = local.getIfPresent(id);
        if (old != null && ShopRedisStore.compareRevision(revision, old.snapshot().revision()) < 0) return;
        if (mode == CacheMode.NORMAL || deleted) retire(id, revision);
        else markExpired(id);
    }

    /**
     * 退役也比较版本：迟到的NORMAL/DELETE任务不能删除同ID重新插入后的HOT副本。
     */
    public void retire(long id, String revision) {
        local.asMap().computeIfPresent(id, (key, old) ->
                ShopRedisStore.compareRevision(revision, old.snapshot().revision()) >= 0 ? null : old);
        retryAfter.remove(id);
    }

    public void remove(long id) {
        local.invalidate(id);
        retryAfter.remove(id);
    }

    public Map<String, Object> statistics() {
        local.cleanUp();
        return Map.of("entries", local.estimatedSize(), "ids", local.asMap().keySet().stream().sorted().toList(),
                "refreshes", refreshes.get(), "inFlight", refreshing.size(), "evictions", local.stats().evictionCount());
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
