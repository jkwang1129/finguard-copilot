package com.finguard.copilot.events;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.observability.SafeSpanData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AnalysisEventStream implements AutoCloseable {
    private final GetAnalysisService access;
    private final AnalysisEventRepository events;
    private final AnalysisWakeups wakeups;
    private final Duration lifetime,poll;
    private final Semaphore capacity=new Semaphore(16);
    private final Map<String,Integer> reviewers=new HashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(16,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{
        var t=new Thread(r,"copilot-sse");t.setDaemon(true);return t;
    });
    @Autowired public AnalysisEventStream(GetAnalysisService access,AnalysisEventRepository events,AnalysisWakeups wakeups) {
        this(access,events,wakeups,Duration.ofSeconds(60),Duration.ofSeconds(1));
    }
    AnalysisEventStream(GetAnalysisService access,AnalysisEventRepository events,AnalysisWakeups wakeups,Duration lifetime,Duration poll) {
        this.access=access;this.events=events;this.wakeups=wakeups;this.lifetime=lifetime;this.poll=poll;
        workers.allowCoreThreadTimeOut(true);
    }
    public SseEmitter open(UUID id,String token,String lastEventId) {
        var authorization=access.authorize(id,token);
        long cursor=cursor(lastEventId);
        if(cursor>events.head(id)) throw new CopilotException(ErrorCode.CONFLICT,"Event cursor is ahead of this analysis");
        String reviewer=authorization.reviewer();
        if(closed.get() || !capacity.tryAcquire()) throw unavailable();
        synchronized(reviewers) {
            int count=reviewers.getOrDefault(reviewer,0);
            if(count>=4){capacity.release();throw unavailable();}
            reviewers.put(reviewer,count+1);
        }
        var emitter=new SseEmitter(lifetime.toMillis());var done=new AtomicBoolean();
        emitter.onCompletion(()->done.set(true));emitter.onError(e->done.set(true));
        emitter.onTimeout(()->{done.set(true);emitter.complete();});
        try {workers.execute(()->serve(id,token,cursor,reviewer,emitter,done));}
        catch(RejectedExecutionException e){release(reviewer);throw unavailable();}
        return emitter;
    }
    private void serve(UUID id,String token,long cursor,String reviewer,SseEmitter emitter,AtomicBoolean done) {
        long deadline=System.nanoTime()+lifetime.toNanos(),heartbeat=0;boolean first=true;
        var last=AnalysisStatus.CONTEXT_READY;
        try(var watch=wakeups.watch(id)) {
            while(!done.get() && !Thread.currentThread().isInterrupted() && System.nanoTime()<deadline) {
                var batch=events.after(id,cursor,64);
                long now=System.nanoTime();
                if(first || !batch.isEmpty() || now-heartbeat>=TimeUnit.SECONDS.toNanos(15)) {
                    var fresh=access.authorize(id,token);
                    if(!reviewer.equals(fresh.reviewer())) throw new CopilotException(ErrorCode.UNAUTHENTICATED,"Core identity changed");
                    last=fresh.view().status();
                    if(first || batch.isEmpty()) emitter.send(SseEmitter.event().comment("connected"));
                    first=false;heartbeat=now;
                    for(var event:batch) {
                        emitter.send(SseEmitter.event().id(event.sequence()).name("analysis-state").data(event));
                        cursor=Long.parseLong(event.sequence());
                    }
                }
                if((last==AnalysisStatus.SUCCEEDED || last==AnalysisStatus.FAILED) && cursor>=events.head(id)) break;
                if(batch.size()<64) watch.await(poll.toMillis());
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(IOException ignored){/* disconnected client */}
        catch(RuntimeException e) {
            try {String code=e instanceof CopilotException c?SafeSpanData.errorCode(c.code().name()):"SERVICE_UNAVAILABLE";
                emitter.send(SseEmitter.event().name("stream-error").data(Map.of("code",code)));
            } catch(Exception ignored){/* client may already be gone */}
        } finally {
            done.set(true);try{emitter.complete();}catch(RuntimeException ignored){}
            release(reviewer);
        }
    }
    private void release(String reviewer) {
        synchronized(reviewers){reviewers.computeIfPresent(reviewer,(key,value)->value<=1?null:value-1);}
        capacity.release();
    }
    static long cursor(String value) {
        if(value==null) return 0;
        try {if(!value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException();return Long.parseLong(value);}
        catch(IllegalArgumentException e){throw new CopilotException(ErrorCode.INVALID_REQUEST,"Last-Event-ID must be a nonnegative 64-bit sequence");}
    }
    int active(){return 16-capacity.availablePermits();}
    private CopilotException unavailable(){return new CopilotException(ErrorCode.SERVICE_UNAVAILABLE,"Progress stream capacity is temporarily unavailable");}
    @Override @jakarta.annotation.PreDestroy public void close(){if(closed.compareAndSet(false,true)) workers.shutdownNow();}
}
