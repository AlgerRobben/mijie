--[[
消费本次失败时记录RETRYING与投递次数。
KEYS[1]结果Hash，ARGV[1]次数。仅PENDING/RETRYING可以变更，避免迟到错误覆盖SUCCEEDED或FAILED。
不删除原消息、不ACK，不设置处理中TTL；真正的重投依靠消费组pending恢复。
]]

local state = redis.call('HGET', KEYS[1], 'state')
if state == 'PENDING' or state == 'RETRYING' then
    redis.call('HSET', KEYS[1], 'state', 'RETRYING', 'attempts', ARGV[1])
end
return 1
