package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * 普通商铺只保存Redis正文；版本落后的已有正文先返回，后台受控刷新源，不安装上层副本。
 */
@Slf4j
@Service
public class NormalShopCacheService {
    private final ShopCacheSource source;
    private final ShopRedisStore store;
    private final ConcurrentHashMap<Long, Object> refreshing = new ConcurrentHashMap<>();
    // 仅存失败退避时间，不存商铺数据；短期过期和容量上限避免普通ID长期占用协调内存。
    private final Cache<Long, Long> retryAfter = Caffeine.newBuilder()
            .maximumSize(1024).expireAfterWrite(1, TimeUnit.SECONDS).build();
    private final ThreadPoolExecutor executor;

    public NormalShopCacheService(ShopCacheSource source, ShopRedisStore store, ShopCacheProperties properties) {
        this.source = source;
        this.store = store;
        int threads = properties.getReplicaConcurrency();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "shop-normal-refresh");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ShopCacheSnapshot get(long id, ShopCacheVersion version) {
        ShopCacheSnapshot cached = store.read(version);
        if (cached == null) return source.await(id); // 真缺失仍同步互斥回填，没有旧值可立即响应。
        // 此处是比较元数据缓存中的版本和商铺缓存中的版本，若不一致则说明出现缓存一致性问题，需要触发重建
        if (ShopRedisStore.compareRevision(cached.revision(), version.revision()) < 0) requestRefresh(id);
        return cached; // 普通信息更新时响应旧快照；不续TTL，也不写Caffeine或边缘。
    }

    private void requestRefresh(long id) {
        Long next = retryAfter.getIfPresent(id);
        if (next != null && next > System.currentTimeMillis()) return;
        Object marker = new Object();
        if (refreshing.putIfAbsent(id, marker) != null) return; // 同JVM同ID只提交一份后台任务。
        try {
            executor.execute(() -> {
                try {
                    ShopCacheVersion current = store.version(id);
                    ShopCacheSnapshot latest = current == null ? null : store.read(current);
                    // 获标记后二次检查：CDC或其他实例已完成回填，就复用结果，不重复访问MySQL。
                    if (current != null && source.usable(latest, current, System.currentTimeMillis())) return;
                    source.await(id); // 源池负责共享Future、分布式锁与原子版本发布；此处只刷新Redis。
                    retryAfter.invalidate(id);
                } catch (Exception error) {
                    retryAfter.put(id, System.currentTimeMillis() + 500);
                    log.warn("SHOP_NORMAL_REFRESH_FAILED id={}，已有正文在原TTL内继续可读", id, error);
                } finally {
                    refreshing.remove(id, marker); // 只清理本次任务的标记，避免误删后来任务。
                }
            });
        } catch (RejectedExecutionException error) {
            refreshing.remove(id, marker);
            retryAfter.put(id, System.currentTimeMillis() + 100);
            log.warn("SHOP_NORMAL_REFRESH_REJECTED id={}，后续查询重试", id);
        }
    }

    int inFlight() {
        return refreshing.size();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
