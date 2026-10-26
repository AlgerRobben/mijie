package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用Mock缓存验证Canal JSON库表过滤、文本ID、主键不可变约束和坏事件拒绝；不代替真实Binlog联调。
 */
class ShopChangeHandlerTest {
    @Test
    void policyEventsUseShopIdInsteadOfASeparateRowId() {
        ShopCacheService cache = mock(ShopCacheService.class);
        new ShopChangeHandler(new ObjectMapper(), cache).handle("{\"database\":\"hmdp\",\"table\":\"tb_shop_cache_version\",\"type\":\"UPDATE\",\"data\":[{\"shop_id\":\"10\"}],\"old\":[{\"revision\":\"2\"}]}");
        verify(cache).evictReliably(10L);
    }

    @Test
    void invalidatesEveryChangedShopFromCanalFlatJson() throws Exception {
        ShopCacheService cache = mock(ShopCacheService.class);
        ShopChangeHandler handler = new ShopChangeHandler(new ObjectMapper(), cache);
        handler.handle("{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"UPDATE\"," +
                "\"data\":[{\"id\":10},{\"id\":11}]}");
        verify(cache).evictReliably(10L);
        verify(cache).evictReliably(11L);
    }

    @Test
    void ignoresUnrelatedTables() throws Exception {
        ShopCacheService cache = mock(ShopCacheService.class);
        ShopChangeHandler handler = new ShopChangeHandler(new ObjectMapper(), cache);
        handler.handle("{\"database\":\"hmdp\",\"table\":\"tb_blog\",\"type\":\"UPDATE\"," +
                "\"data\":[{\"id\":10}]}");
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE"})
    void handlesTextIdsAndDuplicates(String type) {
        ShopCacheService cache = mock(ShopCacheService.class);
        new ShopChangeHandler(new ObjectMapper(), cache).handle("{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"" + type +
                "\",\"data\":[{\"id\":\"10\"},{\"id\":10}]}");
        verify(cache, times(1)).evictReliably(10L);
    }

    @Test
    void ignoresOldIdUnderImmutableShopPrimaryKeyAssumption() {
        ShopCacheService cache = mock(ShopCacheService.class);
        new ShopChangeHandler(new ObjectMapper(), cache).handle("{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"UPDATE\"," +
                "\"data\":[{\"id\":11}],\"old\":[{\"id\":10}]}");
        verify(cache).evictReliably(11L);
        verifyNoMoreInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[]",
            "{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"UPDATE\"}",
            "{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"UPDATE\",\"data\":[{\"id\":\"bad\"}]}"})
    void rejectsBrokenEventsInsteadOfSilentlyAcknowledging(String json) {
        ShopCacheService cache = mock(ShopCacheService.class);
        assertThrows(IllegalArgumentException.class, () -> new ShopChangeHandler(new ObjectMapper(), cache).handle(json));
        verifyNoInteractions(cache);
    }
}
