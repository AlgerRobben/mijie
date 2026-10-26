package com.hmdp.cache;

/**
 * 仅保存商铺版本事实；冷热类别由固定配置决定。删除墓碑使同ID重新插入时版本继续增加。
 */
public record ShopCacheVersion(long shopId, String revision, boolean deleted) {
    public static ShopCacheVersion absent(long id) {
        return new ShopCacheVersion(id, "0", true);
    }
}
