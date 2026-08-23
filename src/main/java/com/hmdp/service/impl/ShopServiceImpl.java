package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
        Shop shop=queryWithMutex(id);
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
}
