--[[
共享详情删除与在线JVM通知在一个Redis执行中完成。
KEYS[1]cache:shop:<id>；ARGV[1]含逻辑库号的通知频道，[2]商铺ID文本。
先DEL再PUBLISH，让收到通知的本地miss读到已删除的共享值；重复删除安全。
Pub/Sub不持久化，断开的JVM可能漏通知；本脚本不负责HTTP清边缘，也不维护GEO。
]]

-- Delete the shared value and notify every connected JVM in one Redis operation.
local removed = redis.call('DEL', KEYS[1])
redis.call('PUBLISH', ARGV[1], ARGV[2])
return removed
