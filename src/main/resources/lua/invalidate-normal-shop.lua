-- KEYS普通数据/策略；ARGV版本/频道/事件/是否属于固定HOT名单。失效也必须防迟到任务删除较新的数据。
-- 清理过时的redis普通商铺缓存
if redis.call('HGET',KEYS[2],'revision') ~= ARGV[1]
        or redis.call('HGET',KEYS[2],'mode') ~= 'NORMAL' then return -1 end
local raw=redis.call('GET',KEYS[1]); local removed=0
if raw then
    local ok,value=pcall(cjson.decode,raw)
    local current=ok and type(value)=='table' and type(value.revision)=='string'
            and value.mode=='NORMAL' and (#value.revision>#ARGV[1]
                or (#value.revision==#ARGV[1] and value.revision>=ARGV[1]))
    if not current then removed=redis.call('DEL',KEYS[1]) end
end
-- 广播移除其他JVM实例的caffeine副本中的热点缓存。
if ARGV[4] == '1' then
    redis.call('PUBLISH', ARGV[2], ARGV[3])
end
return removed
