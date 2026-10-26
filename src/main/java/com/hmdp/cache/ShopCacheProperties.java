package com.hmdp.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 时间单位和容量分别配置：逻辑期限不会交给Caffeine/Redis的物理过期API。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.cache")
public class ShopCacheProperties {
    private Duration loadWait = Duration.ofSeconds(2);
    private Duration redisCommandTimeout = Duration.ofMillis(300);
    private int sourceConcurrency = 4;
    private int replicaConcurrency = 4;
    private String edgeInvalidateUrls = "";
    private String internalKeyFile = ".local/cache-sync/internal-cache-key.txt";
    private Hot hot = new Hot();

    @Data
    public static class Hot {
        private List<Long> ids = List.of(10L);
        private long redisLogicalTtlSeconds = 1800;
        private long localLogicalTtlSeconds = 5;
        private int maximumEntries = 1000;
        private int maximumShops = 500;
        private int maximumSnapshotBytes = 8192;
    }
}
