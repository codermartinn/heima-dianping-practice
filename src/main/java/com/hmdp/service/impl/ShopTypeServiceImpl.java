package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.查询redis中是否有缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        // 2.如果有，直接返回
        List<ShopType> typeList = null;
        if (shopTypeJson != null) {
            typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }


        // 3.如果没有，查询数据库
        typeList = query().orderByAsc("sort").list();

        // 4.把查询出的信息添加到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));

        // 5.返回结果
        return Result.ok(typeList);
    }
}
