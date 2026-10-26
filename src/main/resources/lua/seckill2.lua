--[[
当前秒杀受理的核心Redis脚本：
参数：ARGV[1]券ID、[2]用户ID、[3]订单ID字符串、[4]本实例配置的订单Stream名；KEYS为空，键名在脚本内组装。
步骤：校验活动元数据与Redis服务器时间 → 校验库存/购买标记 → 预检关键类型 → 预扣库存并记录归属 → 写PENDING → XADD。
orderKey是已占用购买资格的用户集合，reservationKey保存“哪个订单拥有本次资格”，二者不是MySQL订单表。
返回：0受理，1库存不足，2重复购买，3未初始化/不安全键状态，4未开始，5已结束。
ID保持字符串，不转Lua浮点数；客户端拿到ID仍需等待后台数据库提交。
Redis原子执行表示命令不被其他客户端交叉插入，不表示脚本错误自动回滚已写操作；预检用于减少常见的部分写入风险。
处理中不设短TTL，防止数据库故障期间资格和消息自然丢失。终态TTL由成功/补偿脚本设置。
]]

--参数列表：优惠券id用来判断库存
--        用户id用来判断一人一单
--        订单id
--        以上三个信息构成order信息需要最终存储到stream消息队列中
-- -----------------------处理参数与key-------------------------------------------------------
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local streamKey = ARGV[4]
-- 判断活动时间的key
-- 活动开始时间key
local beginKey = "seckill:begin:" .. voucherId
-- 活动结束时间key
local endKey = "seckill:end:" .. voucherId

-- 判断秒杀资格的key
-- 预占库存key（库存是否充足）
local stockKey = "seckill:stock:" .. voucherId
-- 已经购买过voucherID的userId集合（是否已经买过）
local orderKey = "seckill:order:" .. voucherId

-- 订单处理的key
-- orderID对应订单的处理状态
local resultKey = "seckill:result:" .. orderId
-- 各优惠券预占的用户到订单的映射，供后续核对或失败补偿
local reservationKey = "seckill:reservation:" .. voucherId

-- ---------------------------业务脚本-----------------------------------------------------------
local stock = redis.call('get', stockKey)
local beginAt = redis.call('get', beginKey)
local endAt = redis.call('get', endKey)
-- --------------各种秒杀资格检验-------------------------------
-- 判断键状态
if(not stock or not beginAt or not endAt) then
    return 3
end
-- 判断活动时间范围是否合法：使用Redis服务器时钟，避免各Java节点时间不一致导致资格判断不同。
local time = redis.call('time')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if(now < tonumber(beginAt)) then
    return 4 -- 活动未开始
end
if(now > tonumber(endAt)) then
    return 5 -- 活动已结束
end
if(tonumber(stock) <= 0) then
    --返回库存不足标志1
    return 1 -- 库存不足
end
if(redis.call('sismember', orderKey, userId) == 1) then
    --返回重复下单标志2
    return 2 -- 重复下单
end
-- 通过上述判断说明有秒杀资格
-- 扣库存之前，确认稍后要写入的 Redis 键可以安全使用，包括stream键和各优惠券预占的用户到订单的映射键
-- 先检查，是为了避免“库存已经扣了，订单消息却因为键类型错误而写不进去”。
local streamType = redis.call('type', streamKey)['ok']
local reservationType = redis.call('type', reservationKey)['ok']
if (streamType ~= 'none' and streamType ~= 'stream')
    or (reservationType ~= 'none' and reservationType ~= 'hash')
    or redis.call('exists', resultKey) == 1 then
    return 3
end
-- 从此处开始写预占，包括扣减库存、增加购买记录以及用户与订单的映射；
-- 只有最终数据库核对确认失败时才由补偿脚本释放。
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', reservationKey, userId, orderId)
-- 向订单处理状态中写入相关信息，该订单对应的用户和优惠券、当前处理状态、受理时间
redis.call('hset', resultKey, 'userId', userId, 'voucherId', voucherId, 'state', 'PENDING', 'acceptedAt', tostring(now))
-- 向stream中写入订单消息
-- 例如：XADD stream.orders * userId 42 voucherId 10 id 90001
-- 这里 * 表示自动生成消息 ID
local messageId = redis.call('xadd', streamKey, '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
-- 完成stream中的订单信息写入后再向订单处理状态中补充stream的消息id
redis.call('hset', resultKey, 'streamId', messageId)
return 0
