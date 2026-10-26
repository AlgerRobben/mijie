package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.dto.Result;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.redis.database=15",
        "hmdp.seckill.stream-consumer.enabled=false",
        "hmdp.canal.enabled=false",
        "hmdp.cache.hot.ids=10"
})
/**
 * 应用上下文下的缓存、热点预热、GEO加载和库存Lua验证。配置使用Redis15号库；属于依赖真实服务的集成测试，不能凭启动类名视为纯内存单测。
 */
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private com.hmdp.cache.ShopCacheClients shopCacheClients;

    @Test
    void rebuildLockWithoutFixedLeaseIsRenewedByWatchdog() throws Exception {
        String key = "test:shop-watchdog:" + UUID.randomUUID();
        var lock = shopCacheClients.locks().getLock(key);
        lock.lock(); // 与业务相同：不传leaseTime，持锁线程存活时续租。
        try {
            long initial = shopCacheClients.template().getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
            boolean renewed = false;
            while (System.nanoTime() < deadline) {
                Thread.sleep(500);
                long remaining = shopCacheClients.template().getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (System.nanoTime() > deadline - java.util.concurrent.TimeUnit.SECONDS.toNanos(8) && remaining > initial - 8000) {
                    renewed = true;
                    break;
                }
            }
            assertEquals(true, renewed);
            assertEquals(true, lock.isHeldByCurrentThread());
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
            shopCacheClients.template().delete(key);
        }
    }

    @Test
    void loadShopLocations() {
        //1.查询所有店铺信息
        List<Shop> shops = shopService.query().list();
        //2.把店铺按照typeId分组
        Map<Long, List<Shop>> shopGroups = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分组写入redis
        for (Map.Entry<Long, List<Shop>> group : shopGroups.entrySet()) {
            String key = RedisConstants.SHOP_GEO_KEY + group.getKey();
            shops = group.getValue();
            //将list<shop>转为list<RedisGeoCommands.GeoLocation<String>>
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            //整组写入redis
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void seckillScriptEnforcesStockAndOneOrderPerUser() {
        String voucherId = "test-" + UUID.randomUUID();
        String stockKey = "seckill:stock:" + voucherId;
        String beginKey = "seckill:begin:" + voucherId;
        String endKey = "seckill:end:" + voucherId;
        String orderKey = "seckill:order:" + voucherId;
        String streamKey = "test:stream:" + voucherId;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill2.lua"));
        script.setResultType(Long.class);
        long now = System.currentTimeMillis();
        try {
            assertEquals(3L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-a", "1000", streamKey));
            stringRedisTemplate.opsForValue().multiSet(Map.of(
                    stockKey, "2", beginKey, Long.toString(now + 60_000), endKey, Long.toString(now + 120_000)));
            assertEquals(4L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-a", "1000", streamKey));
            stringRedisTemplate.opsForValue().set(beginKey, Long.toString(now - 60_000));
            assertEquals(0L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-a", "1001", streamKey));
            assertEquals(2L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-a", "1002", streamKey));
            assertEquals(0L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-b", "1003", streamKey));
            assertEquals(1L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-c", "1004", streamKey));
            assertEquals("0", stringRedisTemplate.opsForValue().get(stockKey));
            assertEquals(2L, stringRedisTemplate.opsForStream().size(streamKey));
            stringRedisTemplate.opsForValue().set(endKey, Long.toString(now - 1));
            assertEquals(5L, stringRedisTemplate.execute(script, List.of(), voucherId, "user-c", "1005", streamKey));
        } finally {
            stringRedisTemplate.delete(List.of(stockKey, beginKey, endKey, orderKey, streamKey,
                    "seckill:reservation:" + voucherId, "seckill:result:1001", "seckill:result:1003"));
        }
    }

    @Test
    void configuredHotShopIsResidentAndMatchesDatabaseDates() {
        Shop database = shopService.getById(10L);
        Result result = shopService.queryById(10L);
        assertEquals(true, result.success());
        assertEquals(10L, ((Shop) result.data()).getId());
        assertEquals(database.getCreateTime(), ((Shop) result.data()).getCreateTime());
        assertEquals(database.getUpdateTime(), ((Shop) result.data()).getUpdateTime());
        assertEquals(-1L, stringRedisTemplate.getExpire("cache:shop:v3:hot:10"));
    }

    @Test
    void missingShopIsCachedAsAnEmptyValue() {
        long missingId = Long.MAX_VALUE;
        try {
            Result result = shopService.queryById(missingId);
            assertEquals(false, result.success());
            assertEquals(true, stringRedisTemplate.opsForValue().get("cache:shop:v3:normal:" + missingId).contains("\"missing\":true"));
        } finally {
            stringRedisTemplate.delete(List.of("cache:shop:v3:normal:" + missingId, "cache:shop:v3:meta:" + missingId));
        }
    }
}
