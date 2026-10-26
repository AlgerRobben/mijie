package com.hmdp.utils;

/**
 * 基础互斥锁接口，描述带过期时间的尝试获取与释放。具体超时、重入和续期能力由实现决定。
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁过期时间
     * @return true:获取锁成功  false:获取锁失败
     */
    boolean tryLock(Long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
