package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基础Redis互斥锁示例：SET NX配合秒级TTL，JVM随机前缀+线程ID标记所有者，Lua原子校验后释放。
 * 不支持可重入和自动续期；锁超时后业务仍可能继续，安全解锁仅防误删新持有者的锁。
 */
public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * SET NX同时写入所有者标识与秒级过期，只有键原先不存在才成功。
     * threadId()是JDK21线程ID接口；JVM随机前缀避免不同进程的线程号相同。
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        //制作标识集群中不同节点的不同线程的唯一id
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        //待存入数据：key为锁住的资源名, value为持有锁的线程的唯一标识
        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 比较持有者和删除必须由一个Lua脚本完成，不能在Java先GET再DEL，否则两步之间锁可能过期换人。
     */
    @Override
    public void unlock() {
//        //释放锁之前判断持有锁的线程是不是本线程，以防误删其他线程的锁
//        String threadId = ID_PREFIX + Thread.currentThread().threadId();
//        //如果发现持有锁的线程就是本线程，则可以释放
//        if(threadId.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + name))){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }

        //调用lua脚本实现原子性的判断与删除
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                List.of(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId());
    }
}
