--[[
安全释放Redis互斥锁。
KEYS[1]锁键，ARGV[1]调用方持有者标识（线程标识或本次回填随机token）。
比较与DEL放在同一脚本，防止Java分两次操作时旧锁已过期、新线程取得锁却被误删。
返回1实际删除，0表示已过期/不属于调用方；脚本不续期，也不能保证超时后的业务互斥。
]]

--比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    --释放锁
    return redis.call('del', KEYS[1])
end
return 0