package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @Description:
 * @author: coderMartin
 * @date: 2022-11-25
 */
public class SimpleRedisLock implements ILock {


    private StringRedisTemplate stringRedisTemplate;
    public static final String KEY_PREFIX = "lock:";
    private String name;

    //用UUID+线程ID拼接成redis的值
    public static final String ID_PREFIX = UUID.randomUUID().toString(true);

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeSec) {
        // 获取线程标识，拼接UUID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeSec, TimeUnit.SECONDS);
        // 拆箱避免空指针风险，如果success为null，返回的就是false
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        // 释放锁

        // 获取当前线程id拼接UUID
        String cur_threadId = ID_PREFIX + Thread.currentThread().getId();
        // 判断当前线程是否与当前持有锁的线程是同一个线程
        String lock_threadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (cur_threadId.equals(lock_threadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
