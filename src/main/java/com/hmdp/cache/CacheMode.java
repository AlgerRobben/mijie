package com.hmdp.cache;

/**
 * 分类由启动时冻结的配置名单确定，不能用缓存JSON字段推断冷热。
 */
public enum CacheMode {NORMAL, HOT}
