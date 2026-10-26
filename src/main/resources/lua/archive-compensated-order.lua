--KEYS[1]	待清理的死信流stream.orders.dlq
--KEYS[2]	归档流stream.orders.compensated
--ARGV[1]	当前死信记录ID
--ARGV[2]	处理结论ORDER_ALREADY_COMMITTED 或 RESERVATION_RELEASED
-- 读取这条死信消息，起始 ID 和结束 ID 相同，精确查询一条记录
local rows = redis.call('XRANGE', KEYS[1], ARGV[1], ARGV[1])
-- 记录不存在直接结束
if #rows == 0 then return 0 end
-- 校验归档流键类型
local archiveType = redis.call('TYPE', KEYS[2])['ok']
if archiveType ~= 'none' and archiveType ~= 'stream' then return redis.error_reply('invalid archive type') end
-- 构建归档消息并插入
-- deadLetterId → 死信记录ID
-- resolution   → 处理结论
-- originalId   → 原订单消息ID
-- attempts     → 投递次数
-- error        → 原失败原因
-- userId、voucherId、id → 原业务字段
local fields = {'deadLetterId', ARGV[1], 'resolution', ARGV[2]}
for i = 1, #rows[1][2], 2 do
    table.insert(fields, rows[1][2][i]); table.insert(fields, rows[1][2][i+1])
end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', '10000', '*', unpack(fields))
-- 删除死信流中的消息
redis.call('XDEL', KEYS[1], ARGV[1])
return 1
