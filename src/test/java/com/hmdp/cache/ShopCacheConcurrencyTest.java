package com.hmdp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用阻塞点主动制造重叠请求，检验合并、旧值非阻塞和发布拒绝，不依赖碰巧同时运行。
 */
class ShopCacheConcurrencyTest {
    @Test
    void strictSourceRebuildsAnExistingOldNormalVersion() throws Exception {
        var repository = mock(ShopCacheRepository.class);
        var store = mock(ShopRedisStore.class);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        var current = new ShopCacheVersion(1, "9", false);
        var old = new ShopCacheSnapshot("1", CacheMode.NORMAL, "8", 0, false, "{\"id\":1,\"name\":\"old\"}");
        when(repository.version(1)).thenReturn(current);
        when(store.read(current)).thenReturn(old);
        when(repository.load(1)).thenReturn(new ShopCacheRepository.State(current, new Shop().setId(1L).setName("new")));
        when(store.json(any())).thenReturn("{\"id\":1,\"name\":\"new\"}");
        when(store.publish(any(), any(), anyString())).thenReturn(true);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.getId()).thenReturn("test");
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        var props = new ShopCacheProperties();
        var source = new ShopCacheSource(repository, store, redisson, props, new ShopCacheCatalog(props), System::currentTimeMillis);
        try {
            assertEquals("9", source.await(1).revision());
            verify(repository, times(1)).load(1);
        } finally {
            source.stop();
        }
    }

    private static ShopCacheVersion policy(CacheMode mode, String version) {
        return new ShopCacheVersion(10, version, false);
    }

    @Test
    void oneDatabaseLoadForOneHundredConcurrentColdRequests() throws Exception {
        var repository = mock(ShopCacheRepository.class);
        var store = mock(ShopRedisStore.class);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        var policy = policy(CacheMode.NORMAL, "1");
        var cached = new AtomicReference<ShopCacheSnapshot>();
        var metadata = new AtomicReference<ShopCacheVersion>();
        when(repository.version(10)).thenAnswer(i -> {
            assertTrue(Thread.currentThread().getName().equals("shop-source-refresh"));
            return policy;
        });
        when(store.version(10)).thenAnswer(i -> metadata.get());
        doAnswer(i -> {
            metadata.set(i.getArgument(0));
            return null;
        }).when(store).observe(any());
        when(store.read(any())).thenAnswer(i -> cached.get());
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.getId()).thenReturn("test-client");
        when(lock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(repository.load(10)).thenAnswer(i -> {
            entered.countDown();
            assertTrue(release.await(1, TimeUnit.SECONDS));
            return new ShopCacheRepository.State(policy, new Shop().setId(10L).setName("fresh"));
        });
        when(store.json(any())).thenReturn("{\"id\":10,\"name\":\"fresh\"}");
        when(store.publish(any(), any(), anyString())).thenAnswer(i -> {
            cached.set(i.getArgument(1));
            return true;
        });
        var props = new ShopCacheProperties();
        var source = new ShopCacheSource(repository, store, redisson, props, new ShopCacheCatalog(props), System::currentTimeMillis);
        var callers = Executors.newFixedThreadPool(100);
        var start = new CyclicBarrier(100);
        List<Future<ShopCacheSnapshot>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++)
                results.add(callers.submit(() -> {
                    start.await();
                    source.version(10);
                    return source.await(10);
                }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            release.countDown();
            for (var result : results) assertEquals("1", result.get(3, TimeUnit.SECONDS).revision());
            verify(repository, times(1)).load(10);
            assertEquals(1, source.databaseLoads());
        } finally {
            release.countDown();
            callers.shutdownNow();
            source.stop();
        }
    }

    @Test
    void expiredLocalSnapshotReturnsBeforeBlockedReplicaRefreshFinishes() throws Exception {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var props = new ShopCacheProperties();
        var currentPolicy = new AtomicReference<>(policy(CacheMode.HOT, "1"));
        when(store.version(10)).thenAnswer(i -> currentPolicy.get());
        when(source.version(10)).thenAnswer(i -> currentPolicy.get());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        long expiry = System.currentTimeMillis() + 1800000;
        var old = new ShopCacheSnapshot("10", CacheMode.HOT, "1", expiry, false, "{\"id\":10,\"name\":\"old\"}");
        var newer = new ShopCacheSnapshot("10", CacheMode.HOT, "2", expiry + 1000, false, "{\"id\":10,\"name\":\"new\"}");
        when(store.read(any())).thenAnswer(i -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return newer;
        });
        when(source.usable(eq(newer), any(), anyLong())).thenReturn(true);
        var hot = new HotShopCacheService(source, store, props, new ShopCacheCatalog(props));
        try {
            hot.install(new ShopCacheView(old, 0));
            currentPolicy.set(policy(CacheMode.HOT, "2"));
            for (int i = 0; i < 100; i++) assertSame(old, hot.cached(10).snapshot());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1L, hot.statistics().get("refreshes"));
            assertEquals(1L, hot.statistics().get("entries")); // 过期未被物理删除。
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!hot.cached(10).snapshot().revision().equals("2") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("2", hot.cached(10).snapshot().revision());
            verify(store, times(1)).read(any());
        } finally {
            release.countDown();
            hot.stop();
        }
    }

    @Test
    void lateOldSnapshotCannotOverwriteNewLocalVersion() {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        when(store.version(10)).thenReturn(policy(CacheMode.HOT, "1"));
        var hot = new HotShopCacheService(source, store, new ShopCacheProperties(), new ShopCacheCatalog(new ShopCacheProperties()));
        try {
            long expiry = System.currentTimeMillis() + 1800000;
            var v2 = new ShopCacheSnapshot("10", CacheMode.HOT, "2", expiry, false, "{\"id\":10}");
            var v1 = new ShopCacheSnapshot("10", CacheMode.HOT, "1", expiry + 1000, false, "{\"id\":10}");
            hot.install(new ShopCacheView(v2, expiry));
            hot.install(new ShopCacheView(v1, expiry));
            assertEquals("2", hot.cached(10).snapshot().revision());
            hot.retire(10, "1");
            assertEquals("2", hot.cached(10).snapshot().revision());
        } finally {
            hot.stop();
        }
    }

    @Test
    void lockTimeoutNeverFallsThroughToUnguardedDatabaseLoad() throws Exception {
        var repository = mock(ShopCacheRepository.class);
        var store = mock(ShopRedisStore.class);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(false);
        var source = new ShopCacheSource(repository, store, redisson, new ShopCacheProperties(), new ShopCacheCatalog(new ShopCacheProperties()), System::currentTimeMillis);
        try {
            assertThrows(CacheBusyException.class, () -> source.await(10));
            verify(repository, never()).load(anyLong());
        } finally {
            source.stop();
        }
    }

    @Test
    void normalResultsNeverEnterHotReplicaCache() {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var hot = new HotShopCacheService(source, store, new ShopCacheProperties(), new ShopCacheCatalog(new ShopCacheProperties()));
        try {
            hot.install(new ShopCacheView(new ShopCacheSnapshot("10", CacheMode.NORMAL, "1", 0, false, "{\"id\":10}"), 0));
            assertNull(hot.cached(10));
            assertEquals(0L, hot.statistics().get("entries"));
            verifyNoInteractions(store);
        } finally {
            hot.stop();
        }
    }

    @Test
    void oneWaitersTimeoutDoesNotCancelTheSharedLoadingTask() throws Exception {
        var repository = mock(ShopCacheRepository.class);
        var store = mock(ShopRedisStore.class);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        var p = policy(CacheMode.NORMAL, "1");
        when(repository.version(10)).thenReturn(p);
        when(store.version(10)).thenReturn(p);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.getId()).thenReturn("test");
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        when(repository.load(10)).thenAnswer(i -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return new ShopCacheRepository.State(p, new Shop().setId(10L));
        });
        when(store.json(any())).thenReturn("{\"id\":10}");
        when(store.publish(any(), any(), anyString())).thenReturn(true);
        var props = new ShopCacheProperties();
        props.setLoadWait(java.time.Duration.ofMillis(300));
        var source = new ShopCacheSource(repository, store, redisson, props, new ShopCacheCatalog(props), System::currentTimeMillis);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> source.await(10));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertInstanceOf(CacheBusyException.class, assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS)).getCause());
            var second = callers.submit(() -> source.await(10));
            Thread.sleep(30);
            release.countDown();
            assertEquals("1", second.get(1, TimeUnit.SECONDS).revision());
            verify(repository, times(1)).load(10);
        } finally {
            release.countDown();
            source.stop();
        }
    }
}
