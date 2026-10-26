package com.hmdp.cache;

/** 内部回源协议；本层5秒期限与Redis30分钟期限分开，复制快照不延长主副本期限。 */
public record ShopCacheView(ShopCacheSnapshot snapshot, long localLogicalExpireAtEpochMillis) { }
