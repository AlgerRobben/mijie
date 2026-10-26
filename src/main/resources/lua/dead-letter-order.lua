-- KEYS[1]: order stream; KEYS[2]: dead-letter stream
-- ARGV: group, consumer, original stream ID, failure reason
-- 获取消息的投递信息，检查消息是否还在order stream的pending列表中并且仍属于该消费者
local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[3], ARGV[3], 1)
if #pending == 0 or pending[1][2] ~= ARGV[2] then
    return 0
end
-- 判断死信流的键类型是否正确
local dlqType = redis.call('TYPE', KEYS[2])['ok']
if dlqType ~= 'none' and dlqType ~= 'stream' then
    return redis.error_reply('dead-letter key is not a stream')
end
-- 读取消息原始正文
local original = redis.call('XRANGE', KEYS[1], ARGV[3], ARGV[3])
local fields = { 'originalId', ARGV[3], 'attempts', tostring(pending[1][4]), 'error', ARGV[4] }
if #original > 0 then
    for i = 1, #original[1][2], 2 do
        table.insert(fields, original[1][2][i])
        table.insert(fields, original[1][2][i + 1])
    end
else
    table.insert(fields, 'missingOriginal')
    table.insert(fields, 'true')
end
-- 写入死信流
redis.call('XADD', KEYS[2], '*', unpack(fields))
if #original > 0 then
    for i = 1, #original[1][2], 2 do
        if original[1][2][i] == 'id' then
            local resultKey = 'seckill:result:' .. original[1][2][i+1]
            local state = nil
            -- 修改订单处理状态
            if redis.call('TYPE', resultKey)['ok'] == 'hash' then state = redis.call('HGET', resultKey, 'state') end
            if state == 'PENDING' or state == 'RETRYING' then
                redis.call('HSET', resultKey, 'state', 'COMPENSATING')
            end
        end
    end
end
-- ACK原消息，原消息会从pending列表中移除
redis.call('XACK', KEYS[1], ARGV[1], ARGV[3])
return 1
