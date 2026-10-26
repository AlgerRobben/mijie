package com.hmdp.utils;

/**
 * Redis键名前缀与时长常量。单位由调用位置的TimeUnit决定，不应只凭常量名猜秒/分；登录、空值、详情和锁的时长用途不同。
 */
public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    // 当前登录调用使用MINUTES，因此36000表示25天；按实际单位理解，不误写成30分钟。
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_BEGIN_KEY = "seckill:begin:";
    public static final String SECKILL_END_KEY = "seckill:end:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
