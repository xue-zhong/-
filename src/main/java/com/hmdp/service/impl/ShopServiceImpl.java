package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
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
    public Result queryById(Long id) throws InterruptedException {
//        Shop shop=queryWithMutex(id);
        Shop shop=queryWithLogicalExpire(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateId(Shop shop) {
        Long id= shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();

    }


    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

//    缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key=CACHE_SHOP_KEY+id;
        //从Redis中查询商铺
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //判断商铺Redis中是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在，直接返回
            Shop shop=JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            return null;
        }

        //Mybatis-puls的内部类，直接调用需要在实体类添加@TableName("tb_shop")来指定操作的哪个表
        //不存在，查询数据库
        Shop shop=getById(id);
        if(shop==null){
            //数据库也不存在，把空值写入Redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在，把数据写入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回商铺信息
        return shop;
    }
        //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) throws InterruptedException {
        String LockKey=LOCK_SHOP_KEY+id;
        String key=CACHE_SHOP_KEY+id;
        //从Redis中查询商铺
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //判断商铺Redis中是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在，直接返回
            Shop shop=JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            return null;
        }
        Shop shop=null;
        try {
            //不存在，获取互斥锁
            Boolean lock= tryLock(LockKey);
            //锁不存在，休眠一段时间后重试
            if(!lock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //Mybatis-puls的内部类，直接调用需要在实体类添加@TableName("tb_shop")来指定操作的哪个表
            //锁存在，查询数据库
            Thread.sleep(200);
            shop=getById(id);
            if(shop==null){
                //数据库也不存在，把空值写入Redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在，把数据写入缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } finally {
            //释放互斥锁
            unlock(LockKey);
        }
        //返回商铺信息
        return shop;
    }

    //
    private static final ExecutorService CACHE_REBUIL_EXECUTOP = Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        //从Redis中查询商铺
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //判断商铺Redis中是否存在
        if(StrUtil.isBlank(shopJson)){
            //不存在，返回null
            return null;
        }
        //取出商铺信息和逻辑过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop=JSONUtil.toBean(data,Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        //缓存命中，判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return shop;
        }
        //过期，获取锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        //获取锁成功，开启独立线程
        if(isLock){
            CACHE_REBUIL_EXECUTOP.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //获取锁失败
        //把商铺数据写入Redis，并重置过期时间
        //释放锁

        //返回商铺信息
        return shop;
    }

    //把店铺信息写入redis（逻辑过期）
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Thread.sleep(200);
        //查询店铺数据
        Shop shop=getById(id);
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
