package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopCacheService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;

/**
 * 商铺详情、更新与按类型/距离查询。详情缓存委托ShopCacheService，提交后普通失效、热点刷新。
 * 距离查询从按类型分组的Redis GEO取得有序ID，再回MySQL读取实体并保持距离顺序。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopCacheService shopCacheService;

    /**
     * 委托统一缓存入口读取详情；null映射成商铺不存在。
     */
    @Override
    public Result queryById(Long id) {
        Shop shop = shopCacheService.get(id);
        return shop == null ? Result.fail("商铺不存在") : Result.ok(shop);
    }

    /**
     * 保留旧调用名称；冷热由固定名单分流，不能从JSON形状推断。
     */
    public Result queryWithLogicExpire(Long id) {
        return queryById(id);
    }

    /**
     * 事务内修改商铺，触发器同步递增数据版本；提交后普通失效、热点立即申请刷新。
     * 回调失败交Canal/MQ重试，版本校验拒绝在途旧回填；仍不承诺全实例瞬间一致。
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空！");
        }
        if (!updateById(shop)) {
            return Result.fail("商铺不存在");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    shopCacheService.evict(id);
                }
            });
        } else {
            shopCacheService.evict(id);
        }
        return Result.ok();
    }

    /**
     * 给新增等已完成写库的调用方提供尽力失效入口，也会清掉可能已有的空值缓存。
     */
    @Override
    public void evictCache(Long id) {
        shopCacheService.evict(id);
    }

    /**
     * 无坐标时普通MySQL分页；有坐标时从该类型GEO中取5公里内按距离升序的前end个ID，再跳过from。
     * 用MySQL FIELD保持GEO给出的顺序，并给返回实体填distance；GEO索引需要事先加载，当前CDC不维护它。
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据位置信息进行查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数并在redis中进行基于位置距离的分页查询
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        //2.1.查出redis中距离排序从0到end的所有商铺
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance() // 包含距离
                        .sortAscending()   // 距离由近及远排序
                        .limit(end)        // 限制查询数量
        );
        if (results == null) {
            return Result.ok(List.of());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            //没有下一页了，结束
            return Result.ok(List.of());
        }
        //2.2.手动截取from到end的商铺提取出商铺id
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            String idStr = result.getContent().getName();
            ids.add(Long.valueOf(idStr));
            distanceMap.put(idStr, result.getDistance());
        });
        //3.根据商铺id查询tb_shops并为shop赋值distance
        String s = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + s + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //4.返回
        return Result.ok(shops);
    }

}
