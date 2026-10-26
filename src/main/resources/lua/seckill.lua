--[[
保留的旧秒杀库存/一人一券演示脚本。
仅ARGV券ID/用户ID，仅扣库存与SADD购买标记，没有活动时间、受理状态、归属凭证或XADD。
当前订单入口使用seckill2.lua；阅读异步订单全链路应以新版脚本为准。
]]

--参数列表：优惠券id用来判断库存
--        用户id用来判断一人一单
local voucherId = ARGV[1]
local userId = ARGV[2]
--redis中的库存key
local stockKey = "seckill:stock:" .. voucherId
--redis中的已经购买过该优惠券的userId集合的key
local orderKey = "seckill:order:" .. voucherId

--脚本业务：判断库存是否大于0且集合中是否已经有userId
if(tonumber(redis.call('get', stockKey)) <= 0) then
    --返回库存不足标志1
    return 1
end
if(redis.call('sismember', orderKey, userId) == 1) then
    --返回重复下单标志2
    return 2
end
--以上判断均不符合说明有秒杀资格，扣减库存并向集合中加入userId
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
