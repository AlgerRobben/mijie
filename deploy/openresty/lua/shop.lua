-- 公开商铺详情。只有HOT可进入边缘缓存；普通数据读取后直接返回，不写上层。
local cache = require "hmdp_cache"
-- 提取url参数，去掉前导0，用于表示合法的Java long
local id = cache.normalize_id(ngx.var.shop_id)
if not id then
    ngx.status = 400;
    ngx.print('{"success":false,"errorMsg":"商铺ID无效"}');
    return
end
if ngx.req.get_method() ~= "GET" and ngx.req.get_method() ~= "HEAD" then
    ngx.exit(405);
    return
end
-- 从边缘缓存提取对应id条目
local cached = cache.peek(id)
-- 若边缘缓存提取到商铺数据则直接返回，中途判断是否逻辑过期并对应安排异步刷新
if cached then
    -- 判断当前时间是否已逻辑过期
    local expired = cached.localLogicalExpireAtEpochMillis <= ngx.now() * 1000
    if expired then
        cache.schedule(id) -- CAS刷新标记只让获胜请求注册异步回调；用户响应不等待Redis/MySQL。
        ngx.header["X-HMDP-Cache-Stale"] = "true" -- 该响应头表明此次返回的是逻辑到期的旧值
    end
    ngx.header["X-HMDP-Cache"] = "shared"
    ngx.print(cached.response) -- 逻辑到期仍返回旧值，正文无物理TTL，不先删除。
    return
end
-- 一次性读取到redis对应商铺的元数据和正文数据
local route = cache.read_redis(id)
local snapshot = route and route.snapshot or nil
if snapshot and route.mode == "NORMAL" then
    -- 核对存在状态
    if snapshot.missing ~= route.deleted then
        snapshot = nil -- 已知删除/重新插入时不复用相反存在状态的快照。
    elseif cache.compare(snapshot.revision, route.revision) < 0 then -- 核对元数据和正文的版本是否一致
        local ok, err = cache.schedule_normal(id) -- 若正文版本落后，则已有普通正文先响应，获标记者在timer中刷新Redis源。
        if not ok then
            ngx.log(ngx.WARN, "SHOP_NORMAL_TIMER_REJECTED id=", id, " ", err)
        end
        ngx.header["X-HMDP-Cache-Stale"] = "true"
    end
end
if snapshot then
    ngx.header["X-HMDP-Cache"] = "redis"
    if snapshot.mode == "HOT" and not snapshot.missing then
        if route.deleted then
            snapshot = nil
        else
            -- 构造边缘缓存视图
            local view = cache.view(snapshot)
            -- 判断是否为旧值（是否逻辑过期或者版本落后）
            local stale = snapshot.logicalExpireAtEpochMillis <= ngx.now() * 1000
                    or cache.compare(snapshot.revision, route.revision) < 0
            if stale then
                view.localLogicalExpireAtEpochMillis = 0;
                cache.schedule(id) -- stale则安排后台异步刷新
                ngx.header["X-HMDP-Cache-Stale"] = "true"
            end
            cache.put(id, view, nil) -- put内部再次核对HOT资格、版本及容量，不接受普通结果。
        end
    end
    if snapshot then
        ngx.print(cache.response(snapshot));
        return
    end
end

-- 真正没有可用快照时，内部Java接口使用Future合并和分布式互斥，超时503而非放开查库。
local view, status = cache.http_get("/internal/shop-cache/" .. id .. "?fresh=true")
if status ~= 200 or not view or not view.snapshot then
    ngx.status = 503;
    ngx.header["Retry-After"] = "1"
    ngx.print('{"success":false,"errorMsg":"服务繁忙，请稍后再试"}');
    return
end
ngx.header["X-HMDP-Cache"] = "backend"
if view.snapshot.mode == "HOT" and not view.snapshot.missing then
    cache.put(id, view, nil)
end
ngx.print(cache.response(view.snapshot)) -- NORMAL回填只在Redis发生，不缓存此HTTP响应。
