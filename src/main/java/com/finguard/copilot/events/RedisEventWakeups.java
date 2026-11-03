package com.finguard.copilot.events;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.stereotype.Component;

/** Lossy bounded wakeups. Redis never carries events, credentials or authoritative state. */
@Component
@ConditionalOnProperty(prefix="finguard.copilot.events",name="redis-enabled",havingValue="true")
public final class RedisEventWakeups implements AutoCloseable {
    private static final String TOPIC="copilot.analysis.changed.v1";
    private final ArrayBlockingQueue<UUID> queue=new ArrayBlockingQueue<>(1000);
    private final Set<UUID> pending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    private final RedisMessageListenerContainer listener=new RedisMessageListenerContainer();
    private final StringRedisTemplate redis;
    private final AnalysisWakeups wakeups;
    private final ExecutorService callbacks=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->daemon(r,"event-redis-callback"),new ThreadPoolExecutor.DiscardPolicy());
    private final ExecutorService subscriptions=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->daemon(r,"event-redis-subscription"),new ThreadPoolExecutor.DiscardPolicy());
    private final Thread worker;
    private volatile boolean subscribed;
    public RedisEventWakeups(RedisConnectionFactory factory,StringRedisTemplate redis,AnalysisWakeups wakeups) {
        this.redis=redis;this.wakeups=wakeups;
        listener.setConnectionFactory(factory);listener.setTaskExecutor(callbacks);listener.setSubscriptionExecutor(subscriptions);
        listener.setMaxSubscriptionRegistrationWaitingTime(300);listener.setRecoveryInterval(5000);
        listener.setErrorHandler(e->subscribed=false);
        listener.addMessageListener((message,pattern)->{
            byte[] bytes=message.getBody();if(bytes.length!=36) return;
            String value=new String(bytes,StandardCharsets.UTF_8);
            if(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) wakeups.signal(UUID.fromString(value));
        },new ChannelTopic(TOPIC));
        listener.afterPropertiesSet();
        wakeups.publisher(this::publishLater);
        worker=daemon(this::work,"event-redis-publisher");
    }
    private static Thread daemon(Runnable runnable,String name){var t=new Thread(runnable,name);t.setDaemon(true);return t;}
    @jakarta.annotation.PostConstruct public void start(){if(started.compareAndSet(false,true)) worker.start();}
    boolean subscribed(){return subscribed;}
    private void publishLater(UUID id){if(!closed.get() && pending.add(id) && !queue.offer(id)) pending.remove(id);}
    private void work() {
        while(!closed.get()) {
            if(!listener.isRunning()) {
                try{listener.start();subscribed=true;}catch(RuntimeException ignored){subscribed=false;}
            }
            try {
                UUID id=queue.poll(1,TimeUnit.SECONDS);
                if(id!=null) try{redis.convertAndSend(TOPIC,id.toString());}catch(RuntimeException ignored){}
                    finally {if(id!=null) pending.remove(id);}
            } catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
    }
    @Override @jakarta.annotation.PreDestroy public void close() {
        if(closed.compareAndSet(false,true)) {
            wakeups.publisher(id->{});worker.interrupt();
            try{listener.destroy();}catch(Exception ignored){}
            callbacks.shutdownNow();subscriptions.shutdownNow();queue.clear();pending.clear();subscribed=false;
        }
    }
}
