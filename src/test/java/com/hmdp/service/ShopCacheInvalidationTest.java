package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.cache.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 提交后尽力刷新不反向影响已提交事务；MQ路径必须传播失败，才能触发Broker重试。
 */
class ShopCacheInvalidationTest {
    @Test
    void committedUpdateDoesNotThrowButMqKeepsTheFailure() {
        var repository = mock(ShopCacheRepository.class);
        var changes = mock(ShopCacheChangeCoordinator.class);
        when(repository.version(10)).thenThrow(new IllegalStateException("MySQL unavailable"));
        doThrow(new IllegalStateException("Refresh unavailable")).when(changes).changed(10);
        var cache = new ShopCacheService(mock(ShopCacheSource.class), mock(ShopRedisStore.class), repository,
                mock(NormalShopCacheService.class), mock(HotShopCacheService.class), mock(ShopCacheCatalog.class), changes, new ObjectMapper());
        try {
            assertDoesNotThrow(() -> cache.evict(10L));
            assertThrows(IllegalStateException.class, () -> cache.evictReliably(10L));
        } finally {
            cache.stop();
        }
    }

    @Test
    void edgeFailureCanBeRetriedWithTheInternalCredential() throws Exception {
        var status = new AtomicInteger(503);
        var calls = new AtomicInteger();
        var edge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        edge.createContext("/internal/cache/shop/10", exchange -> {
            if ("test-key".equals(exchange.getRequestHeaders().getFirst(InternalCacheAccess.HEADER)))
                calls.incrementAndGet();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        edge.start();
        var props = new ShopCacheProperties();
        props.setEdgeInvalidateUrls("http://127.0.0.1:" + edge.getAddress().getPort() + "/internal/cache/shop");
        var access = mock(InternalCacheAccess.class);
        when(access.key()).thenReturn("test-key");
        var notifier = new ShopCacheNotifier(props, access);
        try {
            assertThrows(IllegalStateException.class, () -> notifier.edges(10));
            status.set(200);
            assertDoesNotThrow(() -> notifier.edges(10));
            assertEquals(2, calls.get());
        } finally {
            edge.stop(0);
        }
    }
}
