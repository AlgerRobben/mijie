-- 只在仍是该版本的NORMAL有效路由时删除退役HOT正文，不删除同ID后来重新激活的新版本。
if redis.call('HGET',KEYS[1],'revision') ~= ARGV[1]
        or redis.call('HGET',KEYS[1],'mode') ~= 'NORMAL' then return 0 end
local raw=redis.call('GET',KEYS[2]); if not raw then return 0 end
local ok,value=pcall(cjson.decode,raw)
if ok and type(value)=='table' and type(value.revision)=='string'
        and (#value.revision>#ARGV[1] or (#value.revision==#ARGV[1] and value.revision>ARGV[1])) then return 0 end
return redis.call('DEL',KEYS[2])
