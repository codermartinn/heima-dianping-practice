package com.hmdp.utils;

/**
 * @Description:
 * @author: coderMartin
 * @date: 2022-11-25
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeSec 锁持有的时间，过期后会自动释放
     * @return true代表获取锁成功；false代表获取锁失败
     */
    boolean tryLock(long timeSec);


    /**
     * 释放锁
     */
    void unLock();
}
