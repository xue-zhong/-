package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP=1767225600L;
    private static final int COUNT_BITS=32;
    public long nextId(String keyPrefix){

        //生成时间戳
        LocalDateTime now=LocalDateTime.now();
        //转化为描述
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp =nowSecond-BEGIN_TIMESTAMP;
        //生成序号位
        //使时间变为对应格式的字符串
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长，使用date的目的是为了防止所以id全都在一个key里面，防止超上限，因为只有32位序列号，使用date不仅解决，而且方便管理
        //如果key不存在，redis会先把他当成0，然后累加
        long count= stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        return timestamp << COUNT_BITS | count;
    }



//    public static void main(String[] args) {
//        //获取指定时间的描述,1767225600
//        System.out.println(LocalDateTime.of(2026, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC));
//    }
}
