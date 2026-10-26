-- 数据版本用十进制字符串比较，不把BIGINT转为Lua double。
-- 返回1代表a大
local function cmp(a, b)
    -- 若不存在b，自然a大
    if not b then
        return 1
    end
    -- #a代表字符串长度，长度更长的自然数字更大
    if #a ~= #b then
        return #a > #b and 1 or -1
    end
    if a == b then
        return 0
    end
    return a > b and 1 or -1
end
-- 取得当前元数据
local old = redis.call('HGET', KEYS[1], 'revision')
-- 比较版本号
if cmp(ARGV[2], old) < 0 then
    -- 拒绝版本倒退
    return 0
end
-- 写入新的元数据
redis.call('HSET', KEYS[1], 'id', ARGV[1], 'revision', ARGV[2], 'mode', ARGV[3],
        'deleted', ARGV[4])
-- 设置元数据过期时间
if ARGV[2] == '0' then
    redis.call('EXPIRE', KEYS[1], 120) -- 未知ID只保留短期协调状态，防无效查询产生永久元数据。
else
    redis.call('PERSIST', KEYS[1])
end
return 1
