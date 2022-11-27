package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 代理对象
    private IVoucherOrderService proxy;


    // 提前加载lua文件
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 当一个线程从这个队列中获取元素时，如果没有元素这个线程则会被阻塞，直到队列中有元素，这个线程才会被唤醒。
    private final BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);

    // @PostConstruct是Java自带的注解，在方法上加该注解会在项目启动的时候执行该方法，
    // 也可以理解为在spring容器初始化的时候执行该方法。
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取阻塞队列中订单信息
                    VoucherOrder voucherOrder = orderTask.take();
                    // 创建订单
                    handVoucherOrder(voucherOrder);

                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    // 处理订单
    private void handVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户只能从订单中获取，不能再从ThreadLocal中获取，因为线程不同
        Long userId = voucherOrder.getUserId();
        // 创建锁对象；加锁是为了兜底；在前面判断库存和校验一人一单都在redis中完成了。
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁,失败了立即返回
        boolean isLocked = lock.tryLock();

        if (!isLocked) {
            log.error("不能重复下单");
            return;
        }

        try {
            // 现在是子线程，没办法从ThreadLocal中获取东西
            // 现在想触发事务，如何做？主线程初始化代理对象
            // 子线程获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.执行lua脚本
        // 获取用户信息
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        // 2.判断结果是否为0？
        int res = result.intValue();
        if (res != 0) {
            // 2.1不为0，代表没有购买资格
            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
        }

        // 2.2为0，代表有购买资格，把下单的信息保存到阻塞队列中
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 将订单添加到阻塞队列中
        orderTask.add(voucherOrder);

        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户id
        Long userId = voucherOrder.getUserId();

        // 2.查询tb_voucher_order表中是否只有一条记录？
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        // 3.判断是否存在记录？
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }


        // 4.减扣库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();

        // 5.库存不足
        if (!success) {
            log.error("代金券已经售罄...");
            return;
        }

        // 6.创建订单
        save(voucherOrder);
    }
}
