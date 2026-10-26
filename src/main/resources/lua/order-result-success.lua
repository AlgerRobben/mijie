-- 主要是对订单处理状态resultKey = "seckill:result:" .. orderId作状态转移，由pending切换到succeed
-- 库存扣减、增加购买记录等已经在seckill2.lua中完成
redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'voucherId', ARGV[2], 'state', 'SUCCEEDED')
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return 1
