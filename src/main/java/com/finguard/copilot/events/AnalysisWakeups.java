package com.finguard.copilot.events;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Notifications are hints only. Every reader always returns to MySQL. */
@Component
public final class AnalysisWakeups {
    private final ConcurrentMap<UUID,Set<Watch>> watchers=new ConcurrentHashMap<>();
    private volatile Consumer<UUID> publisher=id->{};
    public void publisher(Consumer<UUID> value){publisher=value;}
    public Watch watch(UUID id){var w=new Watch(id);watchers.computeIfAbsent(id,k->ConcurrentHashMap.newKeySet()).add(w);return w;}
    public void committed(UUID id){signal(id);try{publisher.accept(id);}catch(RuntimeException ignored){/* persistent events remain available */}}
    public void signal(UUID id){var values=watchers.get(id);if(values!=null) values.forEach(w->w.queue.offer(Boolean.TRUE));}
    public final class Watch implements AutoCloseable {
        private final UUID id;private final ArrayBlockingQueue<Boolean> queue=new ArrayBlockingQueue<>(1);
        private Watch(UUID id){this.id=id;}
        public void await(long milliseconds) throws InterruptedException {queue.poll(milliseconds,TimeUnit.MILLISECONDS);}
        public boolean awaitSignal(long milliseconds) throws InterruptedException {return queue.poll(milliseconds,TimeUnit.MILLISECONDS)!=null;}
        @Override public void close(){watchers.computeIfPresent(id,(k,v)->{v.remove(this);return v.isEmpty()?null:v;});}
    }
}
