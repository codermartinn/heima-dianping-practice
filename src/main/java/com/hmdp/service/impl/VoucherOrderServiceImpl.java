package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override

    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("不存在该秒杀券...");
        }

        // 2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚未开始...");
        }

        // 3.判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚已经结束...");
        }

        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("代金券已经售罄...");
        }
        // 5.一人一单逻辑
        // 5.1获取用户id
        Long userId = UserHolder.getUser().getId();

        //获取当前对象的代理对象
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();


        synchronized (userId.toString().intern()) {
            //return this.createVoucherOrder(voucherId);
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单逻辑
        // 5.1获取用户id
        Long userId = UserHolder.getUser().getId();

        // 5.2查询tb_voucher_order表中是否只有一条记录？
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.3判断是否存在记录？
        if (count > 0) {
            return Result.fail("一个人只能购买一张代金券...");
        }


        // 6.减扣库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("代金券已经售罄...");
        }

        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2用户id
        voucherOrder.setUserId(userId);
        // 6.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderId);

    }
}
