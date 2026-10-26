package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由ShopServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 委托统一缓存入口读取详情，传this::getById作为真正需要时才执行的数据库回调；null映射成商铺不存在。
     */
    Result queryById(Long id);
    /**
     * 事务内修改商铺，事务提交后才删除缓存，避免提交前的读请求把旧数据库值再次回填。
     * 失效失败只记录日志，由Canal/MQ再尝试；这个回调不保证更新返回瞬间所有缓存已经一致。
     */
    Result update(Shop shop);
    /**
     * 给新增等已完成写库的调用方提供尽力失效入口，也会清掉可能已有的空值缓存。
     */
    void evictCache(Long id);
    /**
     * 无坐标时普通MySQL分页；有坐标时从该类型GEO中取5公里内按距离升序的前end个ID，再跳过from。
     * 用MySQL FIELD保持GEO给出的顺序，并给返回实体填distance；GEO索引需要事先加载，当前CDC不维护它。
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
