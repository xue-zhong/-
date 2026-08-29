package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUIL_EXECUTOP = Executors.newFixedThreadPool(10);

    //往redis中存入数据并标明过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //往redis中存入数据(为了使用逻辑过期的方式解决缓存穿透)
    public void setWithLogicalExpire (String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        //.toSeconds()把指定的单位时间改为秒,LocalDateTime.now().plusSeconds(n))获取现在在的时间加上n的时间后转化的毫秒数
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    //封装缓存穿透的方法
    //keyPrefix key的前缀,参数Class<R> type是为了让调用者传递R的类型
    //方法内部不能确定需要操作数据库里面的哪个表，所以使调用者传入一段逻辑，Function<R,ID>，ID 参数值类型，R 返回值类型
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key=keyPrefix+id;
        //从Redis中查询商铺
        String json= stringRedisTemplate.opsForValue().get(key);
        //判断商铺Redis中是否存在
        if(StrUtil.isNotBlank(json)){
            //存在，直接返回
            R r=JSONUtil.toBean(json, type);
            return r;
        }
        if(json!=null){
            return null;
        }

        //Mybatis-puls的内部类，直接调用需要在实体类添加@TableName("tb_shop")来指定操作的哪个表
        //不存在，查询数据库
        //Shop shop=getById(id);
        //使用外部传入的逻辑
        R r= dbFallback.apply(id);
        if(r==null){
            //数据库也不存在，把空值写入Redis，防止缓存穿透
            //不能使用time,unit参数，因为空值和具体值所存在时间本就不一样，而且可以写死
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在，把数据写入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit);
        //返回商铺信息
        return r;
    }
    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit ){
        String key=keyPrefix+id;
        //从Redis中查询商铺
        String json= stringRedisTemplate.opsForValue().get(key);
        //判断商铺Redis中是否存在
        if(StrUtil.isBlank(json)){
            //不存在，返回null
            return null;
        }
        //取出商铺信息和逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r=JSONUtil.toBean(data,type);
        LocalDateTime expireTime=redisData.getExpireTime();
        //缓存命中，判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            log.info("11111");
            return r;
        }
        //过期，获取锁
        String lockKey=LOCK_SHOP_KEY+id;
        log.info("2222");
        boolean isLock=tryLock(lockKey);
        //获取锁成功，开启独立线程
        if(isLock){
            CACHE_REBUIL_EXECUTOP.submit(()->{
                try {
                    //this.saveShop2Redis(id,20L);
                    R newR=dbFallback.apply(id);
                    //线程休眠，模拟复杂操作
                    Thread.sleep(200);
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }


        //返回商铺信息
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
