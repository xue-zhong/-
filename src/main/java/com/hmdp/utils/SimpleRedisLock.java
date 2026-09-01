package com.hmdp.utils;

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
    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId=Thread.currentThread().getId();
        Boolean sucess= stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId+"",timeoutSec, TimeUnit.SECONDS);
        //不能直接返回sucess，因为拆包是null会被拆成空指针，会引发异常
        return Boolean.TRUE.equals(sucess);
    }

    @Override
    public void unlock() {
        //删除key，即释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }
}
