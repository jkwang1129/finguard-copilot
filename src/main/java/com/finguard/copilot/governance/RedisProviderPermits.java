package com.finguard.copilot.governance;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import com.finguard.copilot.common.error.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisProviderPermits {
    static final String LEASE_KEY="{finguard-model}:leases:deepseek";
    private static final DefaultRedisScript<Long> ACQUIRE=new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
            redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
            if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end
            local minute=math.floor(now/60000)
            local previous=tonumber(redis.call('HGET',KEYS[2],'minute') or '-1')
            local count=tonumber(redis.call('HGET',KEYS[2],'count') or '0')
            if previous~=minute then count=0 end
            if count>=tonumber(ARGV[3]) then return 0 end
            redis.call('HSET',KEYS[2],'minute',minute,'count',count+1)
            redis.call('PEXPIRE',KEYS[2],120000)
            redis.call('ZADD',KEYS[1],now+tonumber(ARGV[4]),ARGV[1])
            local latest=redis.call('ZRANGE',KEYS[1],-1,-1,'WITHSCORES')
            redis.call('PEXPIRE',KEYS[1],math.ceil(tonumber(latest[2])-now+1000))
            return 1
            """,Long.class);
    private static final DefaultRedisScript<Long> VALID=new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
            local expires=redis.call('ZSCORE',KEYS[1],ARGV[1])
            if expires and tonumber(expires)>now+tonumber(ARGV[2]) then return 1 end
            return 0
            """,Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("return redis.call('ZREM',KEYS[1],ARGV[1])",Long.class);
    private final Supplier<StringRedisTemplate> redis;
    private final String leaseKey;
    private final int concurrency,reviewerPerMinute;
    public record Lease(String ownerToken) {}
    public RedisProviderPermits(StringRedisTemplate redis,int concurrency,int reviewerPerMinute) {this(()->redis,concurrency,reviewerPerMinute);}
    public RedisProviderPermits(Supplier<StringRedisTemplate> redis,int concurrency,int reviewerPerMinute) {
        this(redis,concurrency,reviewerPerMinute,"deepseek");
    }
    public RedisProviderPermits(Supplier<StringRedisTemplate> redis,int concurrency,int reviewerPerMinute,String provider) {
        this.redis=redis;this.concurrency=concurrency;this.reviewerPerMinute=reviewerPerMinute;
        this.leaseKey=switch(provider) {
            case "deepseek" -> LEASE_KEY;
            case "zhipu" -> "{finguard-model}:leases:zhipu";
            default -> throw new IllegalArgumentException("Unsupported review permit provider");
        };
    }
    public Lease acquire(String reviewerKey,Duration timeout) {
        if(reviewerKey==null || !reviewerKey.matches("[0-9a-f]{64}")) throw capacity();
        String owner=UUID.randomUUID().toString();
        long leaseMillis=Math.addExact(Math.multiplyExact(timeout.toMillis(),2),30000);
        try {
            Long granted=redis.get().execute(ACQUIRE,List.of(leaseKey,"{finguard-model}:quota:"+reviewerKey),
                    owner,Integer.toString(concurrency),Integer.toString(reviewerPerMinute),Long.toString(leaseMillis));
            if(!Long.valueOf(1).equals(granted)) throw capacity();
            return new Lease(owner);
        } catch(RuntimeException e) {throw capacity();}
    }
    public boolean valid(Lease lease,Duration requiredRemaining) {
        try {return Long.valueOf(1).equals(redis.get().execute(VALID,List.of(leaseKey),lease.ownerToken(),Long.toString(requiredRemaining.toMillis())));}
        catch(RuntimeException e) {throw capacity();}
    }
    public void release(Lease lease) {
        try {redis.get().execute(RELEASE,List.of(leaseKey),lease.ownerToken());}
        catch(RuntimeException e) {throw capacity();}
    }
    public static CopilotException capacity() {return new CopilotException(ErrorCode.MODEL_CAPACITY_UNAVAILABLE,"model capacity permission unavailable");}
}
