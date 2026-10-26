package com.hmdp.cache;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 固定冷热分类：启动时复制配置名单，运行中不改变。所有实例共享Redis时必须使用相同名单。
 */
@Component
public class ShopCacheCatalog {
    private final Set<Long> hotIds;
    private final long sourceLogicalSeconds;
    private final String fingerprint;

    public ShopCacheCatalog(ShopCacheProperties properties) {
        var configured = properties.getHot();
        TreeSet<Long> ids = new TreeSet<>();
        for (Long id : configured.getIds()) {
            if (id == null || id < 1) throw new IllegalArgumentException("HOT shop IDs must be positive");
            ids.add(id);
        }
        if (ids.size() > configured.getMaximumShops())
            throw new IllegalArgumentException("Fixed HOT list exceeds capacity");
        if (configured.getRedisLogicalTtlSeconds() < 1 || configured.getRedisLogicalTtlSeconds() > 86400)
            throw new IllegalArgumentException("HOT source logical TTL must be 1..86400 seconds");
        hotIds = Set.copyOf(ids);
        sourceLogicalSeconds = configured.getRedisLogicalTtlSeconds();
        try {
            String descriptor = "fixed-v3:" + ids + ":" + sourceLogicalSeconds;
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint fixed HOT configuration", e);
        }
    }

    public boolean isHot(long id) {
        return hotIds.contains(id);
    }

    /**
     * 删除的商铺只保存NORMAL短期不存在占位，固定HOT名单本身仍保留这个ID。
     */
    public CacheMode mode(ShopCacheVersion version) {
        return !version.deleted() && isHot(version.shopId()) ? CacheMode.HOT : CacheMode.NORMAL;
    }

    public List<Long> hotIds() {
        return hotIds.stream().sorted().toList();
    }

    public long sourceLogicalSeconds() {
        return sourceLogicalSeconds;
    }

    public String fingerprint() {
        return fingerprint;
    }
}
