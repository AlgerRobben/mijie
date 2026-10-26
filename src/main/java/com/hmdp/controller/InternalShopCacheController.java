package com.hmdp.controller;

import com.hmdp.cache.InternalCacheAccess;
import com.hmdp.cache.ShopCacheView;
import com.hmdp.service.ShopCacheService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 本机密钥认证的回源/诊断接口。冷热分类固定，没有切换接口或准备模式参数。
 */
@RestController
@RequestMapping("/internal/shop-cache")
public class InternalShopCacheController {
    private final ShopCacheService cache;
    private final InternalCacheAccess access;

    public InternalShopCacheController(ShopCacheService cache, InternalCacheAccess access) {
        this.cache = cache;
        this.access = access;
    }

    @GetMapping("/{id}")
    public ShopCacheView snapshot(@PathVariable long id, @RequestParam(defaultValue = "false") boolean fresh, HttpServletRequest request) {
        access.check(request);
        return fresh ? cache.freshView(id) : cache.view(id);
    }

    @GetMapping("/hot")
    public List<String> hot(HttpServletRequest request) {
        access.check(request);
        return cache.activeHotIds();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest request) {
        access.check(request);
        return cache.statistics();
    }
}
