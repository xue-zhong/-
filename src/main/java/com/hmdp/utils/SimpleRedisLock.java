package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
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
    //为了防止每次都读取文件，使用静态代码块先读取好
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        //创建一个脚本对象
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        //读取对应文件
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //指定脚本执行完返回的类型
        UNLOCK_SCRIPT.setResultType(Long.class );
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        Boolean sucess= stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId,timeoutSec, TimeUnit.SECONDS);
        //不能直接返回sucess，因为拆包是null会被拆成空指针，会引发异常
        return Boolean.TRUE.equals(sucess);
    }


    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),ID_PREFIX+Thread.currentThread().getId()
        );
    }
    //锁释放时加上判断，防止被其他线程误删
/*    @Override
    public void unlock() {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        if(threadId.equals(id)){
        //删除key，即释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }*/
}
