-- KEYS: RLock哈希、版本哈希、数据键。
-- ARGV: owner、revision、mode、JSON、物理TTL、频道、事件、是否属于固定HOT名单。
-- 归属检查、版本/路由检查与SET在一次Lua中完成；不能在Java检查后再普通SET。

-- 首先验证当前任务仍持有对应的锁
if redis.call('HEXISTS', KEYS[1], ARGV[1]) ~= 1 then
    return -1
end

-- 验证待发布的快照版本为最新
if redis.call('HGET', KEYS[2], 'revision') ~= ARGV[2] then
    return -2
end

-- 验证分类一致
if redis.call('HGET', KEYS[2], 'mode') ~= ARGV[3] then
    return -3
end

-- 分类写入，HOT不带物理ttl
if ARGV[3] == 'HOT' then
    redis.call('SET', KEYS[3], ARGV[4]) -- 不带EX/PX，覆盖旧值时也清除旧物理TTL。
else
    redis.call('SET', KEYS[3], ARGV[4], 'EX', ARGV[5])
end

-- 仅固定HOT名单需要通知各JVM的Caffeine；删除HOT时mode为NORMAL，仍须通知退役旧副本。
-- 向redis的Pub/Sub 频道发布事件：
-- {
--  "id": "10",
--  "mode": "HOT",
--  "revision": "8",
--  "deleted": false
-- }
if ARGV[8] == '1' then
    redis.call('PUBLISH', ARGV[6], ARGV[7])
end
return 1
