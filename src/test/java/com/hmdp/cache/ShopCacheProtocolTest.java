package com.hmdp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShopCacheProtocolTest {
    @Test
    void millisecondDeadlinePreservesLargeIdsAndDatePrecision() throws Exception {
        var value = new ShopCacheSnapshot("9007199254740993", CacheMode.HOT, "9007199254740993", 1000, false,
                "{\"id\":9007199254740993,\"createTime\":\"2026-10-08T15:42:47.543743100\"}");
        var mapper = new ObjectMapper();
        var decoded = mapper.readValue(mapper.writeValueAsString(value), ShopCacheSnapshot.class);
        assertTrue(decoded.expired(1001));
        assertEquals(value.dataJson(), decoded.dataJson());
        assertEquals(value.revision(), decoded.revision());
        assertTrue(ShopRedisStore.compareRevision("9007199254740993", "9007199254740992") > 0);
    }

    @Test
    void fixedCatalogIsFrozenAndDeletionDoesNotEraseMembership() {
        var props = new ShopCacheProperties();
        var catalog = new ShopCacheCatalog(props);
        props.getHot().setIds(List.of(11L));
        assertTrue(catalog.isHot(10));
        assertFalse(catalog.isHot(11));
        assertEquals(CacheMode.HOT, catalog.mode(new ShopCacheVersion(10, "1", false)));
        assertEquals(CacheMode.NORMAL, catalog.mode(new ShopCacheVersion(10, "2", true)));
        assertEquals(CacheMode.HOT, catalog.mode(new ShopCacheVersion(10, "3", false)));
    }

    @Test
    void catalogRejectsInvalidIdsOrInsufficientCapacity() {
        var props = new ShopCacheProperties();
        props.getHot().setIds(List.of(0L));
        assertThrows(IllegalArgumentException.class, () -> new ShopCacheCatalog(props));
        props.getHot().setIds(List.of(10L, 11L));
        props.getHot().setMaximumShops(1);
        assertThrows(IllegalArgumentException.class, () -> new ShopCacheCatalog(props));
    }

    @Test
    void catalogFingerprintIgnoresOrderingButDetectsDifferentMembership() {
        var a = new ShopCacheProperties();
        var b = new ShopCacheProperties();
        a.getHot().setIds(List.of(10L, 11L));
        b.getHot().setIds(List.of(11L, 10L, 10L));
        assertEquals(new ShopCacheCatalog(a).fingerprint(), new ShopCacheCatalog(b).fingerprint());
        b.getHot().setIds(List.of(10L));
        assertNotEquals(new ShopCacheCatalog(a).fingerprint(), new ShopCacheCatalog(b).fingerprint());
    }
}
