package com.hmdp.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 主动阻塞源任务，证明普通旧快照响应不等待回填，且相同ID的任务被合并。
 */
class NormalShopCacheServiceTest {
    private static final ShopCacheVersion CURRENT = new ShopCacheVersion(1, "9", false);

    private static ShopCacheSnapshot snapshot(String revision) {
        return new ShopCacheSnapshot("1", CacheMode.NORMAL, revision, 0, false, "{\"id\":1}");
    }

    @Test
    void currentSnapshotDoesNotSubmitSourceWork() {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var current = snapshot("9");
        when(store.read(CURRENT)).thenReturn(current);
        var normal = new NormalShopCacheService(source, store, new ShopCacheProperties());
        try {
            assertSame(current, normal.get(1, CURRENT));
            verifyNoInteractions(source);
        } finally {
            normal.stop();
        }
    }

    @Test
    void trueMissWaitsForControlledSourceResult() {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var current = snapshot("9");
        when(source.await(1)).thenReturn(current);
        var normal = new NormalShopCacheService(source, store, new ShopCacheProperties());
        try {
            assertSame(current, normal.get(1, CURRENT));
            verify(source).await(1);
        } finally {
            normal.stop();
        }
    }

    @Test
    void oneHundredStaleReadsReturnBeforeTheSingleRefreshCompletes() throws Exception {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var old = snapshot("8");
        var latest = new AtomicReference<>(old);
        when(store.read(CURRENT)).thenAnswer(call -> latest.get());
        when(store.version(1)).thenReturn(CURRENT);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(source.await(1)).thenAnswer(call -> {
            assertEquals("shop-normal-refresh", Thread.currentThread().getName());
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            latest.set(snapshot("9"));
            return latest.get();
        });
        var normal = new NormalShopCacheService(source, store, new ShopCacheProperties());
        var start = new CyclicBarrier(100);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ShopCacheSnapshot>> responses = new ArrayList<>();
            for (int i = 0; i < 100; i++)
                responses.add(callers.submit(() -> {
                    start.await();
                    return normal.get(1, CURRENT);
                }));
            for (var response : responses) assertSame(old, response.get(2, TimeUnit.SECONDS));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, normal.inFlight());
            verify(source, times(1)).await(1); // 所有前台已返回，后台仍被release阻塞。
            release.countDown();
            waitUntilIdle(normal);
            assertEquals("9", normal.get(1, CURRENT).revision());
        } finally {
            release.countDown();
            normal.stop();
        }
    }

    @Test
    void winningWorkerRechecksBeforeGoingToMysql() throws Exception {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var old = snapshot("8");
        var fresh = snapshot("9");
        when(store.read(CURRENT)).thenReturn(old, fresh);
        when(store.version(1)).thenReturn(CURRENT);
        var checked = new CountDownLatch(1);
        when(source.usable(eq(fresh), eq(CURRENT), anyLong())).thenAnswer(call -> {
            checked.countDown();
            return true;
        });
        var normal = new NormalShopCacheService(source, store, new ShopCacheProperties());
        try {
            assertSame(old, normal.get(1, CURRENT));
            assertTrue(checked.await(1, TimeUnit.SECONDS));
            waitUntilIdle(normal);
            verify(source, never()).await(anyLong());
        } finally {
            normal.stop();
        }
    }

    @Test
    void failureKeepsOldValueAndBacksOffInsteadOfRetryingEveryRead() throws Exception {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var old = snapshot("8");
        when(store.read(CURRENT)).thenReturn(old);
        when(store.version(1)).thenReturn(CURRENT);
        var attempted = new CountDownLatch(1);
        when(source.await(1)).thenAnswer(call -> {
            attempted.countDown();
            throw new CacheBusyException("source unavailable");
        });
        var normal = new NormalShopCacheService(source, store, new ShopCacheProperties());
        try {
            assertSame(old, normal.get(1, CURRENT));
            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            waitUntilIdle(normal);
            for (int i = 0; i < 100; i++) assertSame(old, normal.get(1, CURRENT));
            verify(source, times(1)).await(1);
        } finally {
            normal.stop();
        }
    }

    @Test
    void fullRefreshPoolDoesNotBlockAnotherOldResponse() throws Exception {
        var source = mock(ShopCacheSource.class);
        var store = mock(ShopRedisStore.class);
        var old = snapshot("8");
        var otherVersion = new ShopCacheVersion(2, "9", false);
        var otherOld = new ShopCacheSnapshot("2", CacheMode.NORMAL, "8", 0, false, "{\"id\":2}");
        when(store.read(CURRENT)).thenReturn(old);
        when(store.version(1)).thenReturn(CURRENT);
        when(store.read(otherVersion)).thenReturn(otherOld);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(source.await(1)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return snapshot("9");
        });
        var props = new ShopCacheProperties();
        props.setReplicaConcurrency(1);
        var normal = new NormalShopCacheService(source, store, props);
        try {
            assertSame(old, normal.get(1, CURRENT));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertSame(otherOld, normal.get(2, otherVersion));
            verify(source, never()).await(2);
            assertEquals(1, normal.inFlight());
        } finally {
            release.countDown();
            normal.stop();
        }
    }

    private static void waitUntilIdle(NormalShopCacheService normal) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (normal.inFlight() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, normal.inFlight());
    }
}
