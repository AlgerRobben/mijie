-- Java/CDC通知：HOT保留正文并提前触发异步刷新；NORMAL及删除清理退役HOT副本。
-- 前面大段代码做各种模块加载，密钥/id解析验证等步骤
local json = require "cjson.safe"
local cache = require "hmdp_cache"
local key = os.getenv("HMDP_CACHE_INTERNAL_KEY")
if not key or ngx.req.get_headers()["X-HMDP-Internal-Key"] ~= key then
    ngx.exit(403);
    return
end
local id = cache.normalize_id(ngx.var.shop_id)
if not id then
    ngx.exit(400);
    return
end
local ok, err = cache.notify(id)
ngx.header.content_type = "application/json"
if not ok then
    ngx.status = 503;
    ngx.log(ngx.WARN, "SHOP_EDGE_NOTIFICATION_FAILED id=", id, " ", err)
end
ngx.print(json.encode({ success = ok or false }))
