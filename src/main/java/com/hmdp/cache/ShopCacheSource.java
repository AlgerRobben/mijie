package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 主副本重建：同JVM同ID共用Future，跨JVM靠RLock；实际工作线程获取/释放锁以启用watchdog。
 * 无队列的受限执行器不会囤积持锁任务。所有等待者超时都不能取消共享任务或无锁回源。
 */
@Slf4j
@Service
public class ShopCacheSource {
    private final ConcurrentHashMap<Long, CompletableFuture<ShopCacheSnapshot>> loading = new ConcurrentHashMap<>();
    private final ShopCacheRepository repository;
    private final ShopRedisStore store;
    private final RedissonClient redisson;
    private final ShopCacheProperties properties;
    private final ShopCacheCatalog catalog;
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor finalReads;
    private final LongSupplier now;
    private final AtomicLong databaseLoads = new AtomicLong();

    @Autowired
    public ShopCacheSource(ShopCacheRepository repository, ShopRedisStore store, ShopCacheClients clients,
                           ShopCacheProperties properties, ShopCacheCatalog catalog) {
        this(repository, store, clients.locks(), properties, catalog, System::currentTimeMillis);
    }

    ShopCacheSource(ShopCacheRepository repository, ShopRedisStore store, RedissonClient redisson,
                    ShopCacheProperties properties, ShopCacheCatalog catalog, LongSupplier now) {
        this.repository = repository;
        this.store = store;
        this.redisson = redisson;
        this.properties = properties;
        this.now = now;
        this.catalog = catalog;
        int threads = properties.getSourceConcurrency();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> {
                    Thread t = new Thread(task, "shop-source-refresh");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        finalReads = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> {
                    Thread t = new Thread(task, "shop-final-cache-read");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public ShopCacheVersion version(long id) {
        if (id < 1) throw new IllegalArgumentException("Invalid shop ID");
        ShopCacheVersion version = store.version(id);
        if (version == null) {
            // 元数据全失也合并同ID请求，不能让每个请求线程直接查版本表。
            await(id);
            version = store.version(id);
            if (version == null) throw new CacheBusyException("Cache version disappeared during recovery");
        }
        return version;
    }

    public boolean usable(ShopCacheSnapshot snapshot, ShopCacheVersion version, long time) {
        return snapshot != null && snapshot.mode() == catalog.mode(version)
                && ShopRedisStore.compareRevision(snapshot.revision(), version.revision()) >= 0
                && snapshot.missing() == version.deleted() && !snapshot.expired(time);
    }

    public ShopCacheSnapshot await(long id) {
        if (id < 1) throw new IllegalArgumentException("Invalid shop ID");
        Long key = id;
        long deadline = System.nanoTime() + properties.getLoadWait().toNanos();
        // CompletableFuture<T>意味这未来要向其中填入一个T类型的对象
        CompletableFuture<ShopCacheSnapshot> candidate = new CompletableFuture<>();
        // concurrentHashMap充当实例锁，当第一个线程成功put时，future = null，用这个过程模拟抢锁
        CompletableFuture<ShopCacheSnapshot> future = loading.putIfAbsent(key, candidate);
        if (future == null) {
            future = candidate;
            try {
                executor.execute(() -> {
                    try {
                        // 向candidate中填入loadLocked的返回值，即最新redis快照数据，填入后尝试get的线程会被唤醒并返回
                        candidate.complete(loadLocked(id, deadline));
                    } catch (Throwable error) {
                        candidate.completeExceptionally(error);
                    } finally {
                        loading.remove(key, candidate);
                    }
                });
            } catch (RejectedExecutionException error) {
                loading.remove(key, candidate);
                candidate.completeExceptionally(new CacheBusyException("Cache loading capacity exhausted", error));
            }
        }
        try {
            // get(timeout)只结束当前等待者，不对共享Future调用orTimeout/cancel。
            long remaining = deadline - System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(100);
            if (remaining <= 0) throw new TimeoutException();
            // 尝试get future中的ShopCacheSnapShot，若无，则线程挂起等待，直到被唤醒并返回快照
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheBusyException("Cache loading interrupted", e);
        } catch (TimeoutException e) {
            return finalReadOrFail(id, deadline, e);
        } catch (ExecutionException e) {
            return finalReadOrFail(id, deadline, e.getCause());
        }
    }

    private ShopCacheSnapshot finalReadOrFail(long id, long deadline, Throwable cause) {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            Future<ShopCacheSnapshot> read = null;
            try {
                read = finalReads.submit(() -> {
                    ShopCacheVersion version = store.version(id);
                    if (version == null) return null;
                    ShopCacheSnapshot value = store.read(version);
                    return usable(value, version, now.getAsLong()) ? value : null;
                });
                ShopCacheSnapshot value = read.get(remaining, TimeUnit.NANOSECONDS);
                if (value != null) return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {/* 没有剩余预算或最终读取容量时受控失败，不额外回源。 */} finally {
                if (read != null && !read.isDone()) read.cancel(true);
            }
        }
        throw new CacheBusyException("No cache result within the loading budget", cause);
    }

    private ShopCacheSnapshot loadLocked(long id, long deadline) throws InterruptedException {
        RLock lock = redisson.getLock(ShopRedisStore.lockKey(id));
        long wait = Math.max(0, deadline - System.nanoTime());
        // 不指定leaseTime，Redisson 3.13.6会使用watchdog续租；此线程负责最后unlock。
        if (!lock.tryLock(wait, TimeUnit.NANOSECONDS)) throw new CacheBusyException("Cache refresh lock busy");
        try {
            // 每个抢到redisson锁的线程尝试三轮重建，若失败则主动释放锁
            for (int attempt = 0; attempt < 3; attempt++) {
                // 查询MySQL版本表拿到最新版本号
                // SELECT shop_id, revision, deleted FROM tb_shop_cache_version WHERE shop_id = ?
                ShopCacheVersion version = repository.version(id);
                // 通过lua脚本更新对应商铺的redis元数据
                store.observe(version);
                // 读取redis已有的正文数据
                ShopCacheSnapshot existing = store.read(version);
                // 拿锁后二次检查：检查通过则不需要再查MySQL正文表
                // 检查内容包括：正文存在，存在状态/热点分类/版本号等与元数据相同或者不落后且逻辑未过期
                if (usable(existing, version, now.getAsLong())) return existing;
                if (System.nanoTime() >= deadline) throw new CacheBusyException("Cache loading deadline exceeded");
                databaseLoads.incrementAndGet();
                // 正文表与版本表联表查询
                ShopCacheRepository.State state = repository.load(id);
                version = state.version();
                // 使用这次查出的版本号更新redis元数据
                store.observe(version);
                CacheMode mode = catalog.mode(version);
                boolean missing = state.shop() == null;
                long expires = mode == CacheMode.HOT ? now.getAsLong() + 1000 * catalog.sourceLogicalSeconds() : 0;
                // 快照构建
                ShopCacheSnapshot snapshot = new ShopCacheSnapshot(Long.toString(id), mode, version.revision(),
                        expires, missing, missing ? null : store.json(state.shop()));
                String owner = redisson.getId() + ":" + Thread.currentThread().threadId();
                // 快照发布完成redis缓存更新并返回最新快照
                // 同时lua脚本里还向redis的pub/sub频道发送广播事件，让其他Java实例根据事件更新本地的caffeine副本
                if (store.publish(version, snapshot, owner)) {
                    log.info("SHOP_CACHE_PUBLISHED id={} mode={} revision={} missing={}", id, mode, version.revision(), missing);
                    return snapshot;
                }
                // 发布被拒绝时有限重读当前版本，不能用不受保护的SET覆盖新值。
                if (!lock.isHeldByCurrentThread()) throw new CacheBusyException("Cache refresh lease lost");
            }
            throw new CacheBusyException("Shop changed repeatedly during cache loading");
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public long databaseLoads() {
        return databaseLoads.get();
    }

    public int inFlight() {
        return loading.size();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        finalReads.shutdownNow();
    }
}
