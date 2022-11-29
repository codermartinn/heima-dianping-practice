package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        //shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void test1() throws InterruptedException {
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id:" + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void addShopGeo() {
        // 1.查询数据库所有的店铺信息
        List<Shop> shopList = shopService.list();

        // 2.将店铺按店铺类型进行分类，按type_id分类,写到一个HashMap<店铺类型，相同类型的店铺集合>
        Map<Long, List<Shop>> map = shopList
                .stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));


        // 3.分批写入redis中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1获取店铺类型id
            Long shopTypeId = entry.getKey();
            // 3.2获取所有同类型的店铺
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + shopTypeId;
            for (Shop shop : shops) {
                // 写入redis
                stringRedisTemplate.opsForGeo().add(key,
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString()
                );
            }
        }
    }

}
