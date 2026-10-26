package com.hmdp.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 启动只预热固定名单中的有效商铺；不存在的配置ID不占上层容量。
 */
class ShopCacheBootstrapTest {
    @Test
    void warmsOnlyExistingFixedHotIds() {
        var repository = mock(ShopCacheRepository.class);
        var store = mock(ShopRedisStore.class);
        var source = mock(ShopCacheSource.class);
        var hot = mock(HotShopCacheService.class);
        var props = new ShopCacheProperties();
        props.getHot().setIds(List.of(10L, 99L));
        var v = new ShopCacheVersion(10, "2", false);
        when(repository.versions()).thenReturn(List.of(v, new ShopCacheVersion(11, "3", false)));
        when(repository.version(10)).thenReturn(v);
        when(repository.version(99)).thenReturn(ShopCacheVersion.absent(99));
        var snapshot = new ShopCacheSnapshot("10", CacheMode.HOT, "2", 2000000000000L, false, "{}");
        var view = new ShopCacheView(snapshot, 1000);
        when(source.await(10)).thenReturn(snapshot);
        when(hot.view(snapshot)).thenReturn(view);
        new ShopCacheBootstrap(repository, store, source, hot, new ShopCacheCatalog(props)).warm();
        verify(source).await(10);
        verifyNoMoreInteractions(source);
        verify(hot).install(view);
    }
}
