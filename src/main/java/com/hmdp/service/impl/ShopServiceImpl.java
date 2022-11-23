package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithCachePenetration(id);
        // 使用工具类，缓存穿透
        //cacheClient.queryWithCachePenetration(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES)

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithCacheBreakdown(id);

        // 逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicExpire(id);
        // 使用工具类，逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商铺不存在...");
        }

        // 7.返回
        return Result.ok(shop);
    }

/*    public Shop queryWithCachePenetration(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否命中;isNotBlank只有真正有字符串时才为true
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //shopJson只剩两种情况:1.null 2.""

        // 判断命中的是不是空值;
        //null是没有地址
        //""是有地址但是里面的内容是空的
        if (shopJson != null) {
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5.商铺不存在，返回错误
        if (shop == null) {
            // 将空值写入redis,防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6.商铺存在，写入Redis,设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }*/


/*    public Shop queryWithCacheBreakdown(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否命中;isNotBlank只有真正有字符串时才为true
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //shopJson只剩两种情况:1.null 2.""

        // 判断命中的是不是空值;
        //null是没有地址
        //""是有地址但是里面的内容是空的
        if (shopJson != null) {
            return null;
        }

        // 4.基于互斥锁实现缓存重建
        // 4.1获取互斥锁;锁的缓存的key跟 商铺缓存的key 不是一个key
        Shop shop = null;
        try {
            boolean isLocked = tryLock(LOCK_SHOP_KEY);
            // 4.2判断是否获取成功
            if (!isLocked) {
                // 4.3失败，则休眠并重试
                Thread.sleep(50);
                queryWithCacheBreakdown(id);
            }

            // 4.4成功，根据id查询数据库
            // 模拟复杂业务的查询
            Thread.sleep(200);
            shop = getById(id);

            // 5.商铺不存在，返回错误
            if (shop == null) {
                // 将空值写入redis,防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6.商铺存在，写入Redis,设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unLock(LOCK_SHOP_KEY);
        }

        // 8.返回
        return shop;
    }*/


    //逻辑过期
/*    public Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中，直接返回
            return null;
        }

        // 4.redis命中
        // 4.1 把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1未过期，直接返回店铺信息
            return shop;
        }


        // 5.2已过期，需要缓存重建
        // 6.缓存重建
        // 6.1尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);
        // 6.2判断是否获取锁
        if (isLocked) {
            // 6.3获取成功，开启独立线程
            //注意：获取锁成功应该再次检测redis缓存是否过期，做DoubleCheck。如果存在则无需重建缓存。
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }


        // 6.4 获取失败，返回商铺信息；获取锁成功后，也是直接返回旧的缓存

        return shop;
    }*/


    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空...");
        }

        // 1.更新数据库
        updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

/*    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询商铺信息
        Shop shop = getById(id);
        // 模拟复杂业务查询
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/


}
