package com.hmdp.service.impl;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private VoucherOrderController voucherOrderController;
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //异步秒杀
    //基于redis完成秒杀判断
    public Result seckillVoucher(Long voucherId) {
        //准备参数，用户id，优惠劵id（本就传入）
        Long userId=UserHolder.getUser().getId();
        //执行lua脚本
        Long result =stringRedisTemplate.execute(
                //传入脚本参数
                SECKILL_SCRIPT,
                //由于没有key参数，所以传入空集合
                Collections.emptyList(),
                //传入其他参数，订单id，用户id
                voucherId.toString(),userId.toString()
        );
        //将其转换为int
        int r=result.intValue();
        if(result!=0){
            return Result.fail(result==1?"库存不足":"不能重复下单");
        }
        //使用工具类获取随机订单id
        long orderId=redisIdWorker.nextId("order");
        return Result.ok(orderId);
    }


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠劵信息(使用本函数getById只能查看订单，应使用SeckillVoucherServiceImpl的)
        SeckillVoucher seckillVoucher=seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            //未开始，返回异常结果
            return Result.fail("秒杀尚未开始");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            //已结束，返回异常结果
            return Result.fail("秒杀已经结束");
        }
        //已开始，判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if(stock<1){
            //已无库存，返回异常结果
            return Result.fail("库存不足");

        }
        Long userId= UserHolder.getUser().getId();
        //intern()是到字符串池去拿原有的字符串，如果没有再创建新的，如果不使用该方法，会使每次都会
    //new一个新的字符串，这样锁就不一致了，就不能起到防护作用
        //001.这样做事务不生效，需要解决
//        synchronized (userId.toString().intern()){
//        SimpleRedisLock lock=new SimpleRedisLock(stringRedisTemplate,"order"+userId);
        RLock lock=redissonClient.getLock("lock:order:"+userId);
        boolean isLock= lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            //002.拿到当前对象的代理对象
            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();

            //返回订单id
            //003.使用proxy.createVoucherOrder(voucherId)还需要启动代理对象（在启动类）和引入依赖，（还需要在接口里创建一下）
            return proxy.createVoucherOrder(voucherId);
//            return createVoucherOrder(voucherId);
//        }
        }finally {
            lock.unlock();
        }

    } */
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        Long userId= UserHolder.getUser().getId();
        //查询订单，即该用户是否已下过单
        int count =query().eq("user_id",userId).eq("voucher_id",voucherId).count();
        if(count>0){
            //订单已存在，该用户购买过一次
          return Result.fail("该用户购买过了");
        }

        //仍有库存，扣减库存
        //seckillVoucher.setStock(stock-1);
        //seckillVoucherService.save(seckillVoucher);
        Boolean success= seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id",voucherId).gt("stock",0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        Long orderId=redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);

        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
