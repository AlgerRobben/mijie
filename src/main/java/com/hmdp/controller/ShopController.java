package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 商铺HTTP接口，匿名路径由MvcConfig配置；详情走服务缓存，写入触发对应失效。
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * GET /shop/{id}详情入口。完整边缘请求先经过OpenResty，只有需要Java回源时才来到这里。
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺保存成功后清掉该ID已有的正向/空值缓存，返回数据库生成的商铺ID。
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        if (!shopService.save(shop)) {
            return Result.fail("保存商铺失败");
        }
        shopService.evictCache(shop.getId());
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * PUT /shop委托事务服务更新；真正的缓存失效在事务提交后。
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    /**
     * typeId与页码必需，x/y可选；服务据坐标是否齐全选择普通分页或GEO距离查询。
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 直接MySQL LIKE分页，名称为空时不附加LIKE条件；这一列表查询不经过详情缓存。
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
