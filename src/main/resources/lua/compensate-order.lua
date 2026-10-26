-- 调用前已由Java确认并提交MySQL失败围栏。
-- KEYS: result, stock, purchasers, reservation owners, durable compensation receipts.
-- ARGV: orderId, userId, voucherId, existing committed orderId (or empty), terminal TTL.

-- 先查有无对应orderID的补偿key，判断是否已经补偿过，若已经补偿过直接返回
if redis.call('HEXISTS', KEYS[5], ARGV[1]) == 1 then
    return 1
end
-- 判断订单处理状态是否已经是SUCCEEDED，已成功处理的订单不补偿
if redis.call('HGET', KEYS[1], 'state') == 'SUCCEEDED' then
    return 0
end
-- 判断马上要释放的预占库存和要清理的购买记录的userID，voucherID和orderID是否对的上
if redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2]
    or redis.call('HGET', KEYS[1], 'voucherId') ~= ARGV[3]
    or redis.call('HGET', KEYS[4], ARGV[2]) ~= ARGV[1] then
    return 0
end
local stock = redis.call('GET', KEYS[2])
if not stock or not tonumber(stock) then return 0 end
local purchaserType = redis.call('TYPE', KEYS[3])['ok']
if purchaserType ~= 'set' then return 0 end
-- 全部校验通过，释放预占
redis.call('INCRBY', KEYS[2], 1)
if ARGV[4] == '' then
    redis.call('SREM', KEYS[3], ARGV[2])
    redis.call('HDEL', KEYS[4], ARGV[2])
else
    redis.call('SADD', KEYS[3], ARGV[2])
    redis.call('HSET', KEYS[4], ARGV[2], ARGV[4])
end
redis.call('HSET', KEYS[5], ARGV[1], '1')
redis.call('HSET', KEYS[1], 'state', 'FAILED', 'compensated', '1')
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
return 1
