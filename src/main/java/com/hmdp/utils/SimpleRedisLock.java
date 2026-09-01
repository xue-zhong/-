package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name){
        this.stringRedisTemplate=stringRedisTemplate;
        this.name=name;
    }
    private static final String KEY_PREFIX="lock";
    //UUID必须是糊涂包里面的toString才可以使用true去掉“-”
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        Boolean sucess= stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId,timeoutSec, TimeUnit.SECONDS);
        //不能直接返回sucess，因为拆包是null会被拆成空指针，会引发异常
        return Boolean.TRUE.equals(sucess);
    }
    //锁释放时加上判断，防止被其他线程误删
    @Override
    public void unlock() {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        if(threadId.equals(id)){
        //删除key，即释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }
}
