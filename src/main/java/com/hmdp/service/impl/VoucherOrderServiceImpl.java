package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.StringReader;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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


    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    //@PostConstruct表示当前类在初始化完毕就执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //线程任务(消息队列)
    private class VoucherOrderHandler implements Runnable {
        String quname = "stream.orders";

        @SneakyThrows
        @Override
        public void run() {
            while (true) {
                //获取队列中的首个元素，如果没有元素，就一直阻塞
                try {
                    //获取消息队列中的信息
                    //XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS key [key ...] ID [ID ...]
                    //代码后面写.var后回车自动生成代码左边
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            //读取选项
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //lastConsumed最近一次为消费的消息
                            StreamOffset.create(quname, ReadOffset.lastConsumed())

                    );
                    //判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，等待下一次循环
                        continue;
                    }
                    //解析消息中的订单消息（队列中只有一个消息）
                    //String, Object, Object类型分别为key的类型，value(getValue)的类型，消息id的类型
                    MapRecord<String, Object, Object> record = list.get(0);
                    //map也是Object
                    Map<Object, Object> values = record.getValue();
                    //true为是否忽略赋值异常
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //获取成功，下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    //record.getId()获取消息id
                    stringRedisTemplate.opsForStream().acknowledge(quname, "g1", record.getId());
                } catch (Exception e) {
                    handlePendingList();
                    log.error("错误");
                }
            }
        }
    }
/*    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);

    //线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                //获取队列中的首个元素，如果没有元素，就一直阻塞
                try {
                    VoucherOrder voucherOrder= orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("错误");
                }
            }
        }
    }*/
    //提取获取代理对象
    private IVoucherOrderService proxy;
    //异步秒杀
    //基于redis完成秒杀判断(使用消息队列)
    public Result seckillVoucher(Long voucherId) {
        //准备参数，用户id，优惠劵id（本就传入）
        Long userId=UserHolder.getUser().getId();
        //使用工具类获取随机订单id
        long orderId=redisIdWorker.nextId("order");
        //执行lua脚本
        Long result =stringRedisTemplate.execute(
                //传入脚本参数
                SECKILL_SCRIPT,
                //由于没有key参数，所以传入空集合
                Collections.emptyList(),
                //传入其他参数，订单id，用户id,订单id(和前两者使用方法不一样是因为orderId是long类型，基本类型，而Long是对象)
                voucherId.toString(),userId.toString(),String.valueOf(orderId)
        );
        //将其转换为int
        int r=result.intValue();
        if(result!=0){
            return Result.fail(result==1?"库存不足":"不能重复下单");
        }
        //不在使用阻塞队列了
        //提取获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
 /*   //异步秒杀
    //基于redis完成秒杀判断(使用阻塞队列)
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
        //创建订单对象放入阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        //将优惠券id、用户id和订单id存入voucherOrder
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);
        //提取获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/



    private void handleVoucherOrder(VoucherOrder voucherOrder){
        //获取用户,换线程了，拿不到之前的线程用户id了
        Long userId=voucherOrder.getUserId();
        //创建锁对象
        RLock lock=redissonClient.getLock("lock:order:"+userId);
        boolean isLock= lock.tryLock();
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象，换成子线程了，提取获取代理对象，传入进来
//            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();

            proxy.createVoucherOrder(voucherOrder);

//            return createVoucherOrder(voucherId);
//        }
        }finally {
            lock.unlock();
        }
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
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId= voucherOrder.getUserId();
        //查询订单，即该用户是否已下过单
        int count =query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
            //订单已存在，该用户购买过一次
           log.error("该用户购买过了");
           return;
        }

        //仍有库存，扣减库存
        //seckillVoucher.setStock(stock-1);
        //seckillVoucherService.save(seckillVoucher);
        Boolean success= seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)
                .update();
        if(!success){
            log.error("库存不足");
            return;
        }
//        //创建订单
//        VoucherOrder voucherOrder=new VoucherOrder();
//        Long orderId=redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);

//        voucherOrder.setVoucherId(voucherOrder);
        save(voucherOrder);
    }
    private void handlePendingList() throws InterruptedException {

        while (true) {
            //获取队列中的首个元素，如果没有元素，就一直阻塞
            try {
                //获取pending-list中的信息
                //XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS key [key ...] ID [ID ...]
                //代码后面写.var后回车自动生成代码左边
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        //读取选项
                        StreamReadOptions.empty().count(1),
                        //lastConsumed最近一次为消费的消息
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))

                );
                //判断消息是否获取成功
                if (list == null || list.isEmpty()) {
                    //获取失败，等待下一次循环
                    break;
                }
                //解析消息中的订单消息（队列中只有一个消息）
                //String, Object, Object类型分别为key的类型，value(getValue)的类型，消息id的类型
                MapRecord<String, Object, Object> record = list.get(0);
                //map也是Object
                Map<Object, Object> values = record.getValue();
                //true为是否忽略赋值异常
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //获取成功，下单
                handleVoucherOrder(voucherOrder);
                //ACK确认
                //record.getId()获取消息id
                stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
            } catch (Exception e) {

                log.error("错误");
                Thread.sleep(50);

            }
        }
    }
}

