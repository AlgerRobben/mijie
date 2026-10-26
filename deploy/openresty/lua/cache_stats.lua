-- 本机诊断入口，需共享密钥；便于验证完整热点名单、字典实际占用及普通流量不入上层。
-- 先验证共享密钥
-- 密钥无误后调用 hmdp_cache.stats()返回:
--     entries(边缘字典中的驻留条目计数)
--     ids(缓存中的商铺 ID 集合)
--     capacity(共享字典总容量，单位字节)
--     freeSpace(共享字典可用空间，单位字节)
-- 当前配置没有定时调用这个接口；人工检查或测试脚本通过 HTTP 请求GET http://127.0.0.1:8080/internal/cache/stats触发
local key=os.getenv("HMDP_CACHE_INTERNAL_KEY")
if not key or ngx.req.get_headers()["X-HMDP-Internal-Key"]~=key then ngx.exit(403);return end
ngx.header.content_type="application/json"
ngx.print(require("cjson.safe").encode(require("hmdp_cache").stats()))
