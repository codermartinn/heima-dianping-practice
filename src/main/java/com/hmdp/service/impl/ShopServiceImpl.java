package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithCachePenetration(id);

        // 互斥锁解决缓存击穿
        Shop shop = queryWithCacheBreakdown(id);
        if (shop == null){
            return Result.fail("商铺不存在...");
        }

        // 7.返回
        return Result.ok(shop);
    }

    public Shop queryWithCachePenetration(Long id) {
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
    }


    public Shop queryWithCacheBreakdown(Long id) {
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
    }


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

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
