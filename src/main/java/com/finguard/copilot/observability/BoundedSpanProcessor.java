package com.finguard.copilot.observability;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/** Nonblocking producer, one daemon consumer per destination; no retry or disk spool. */
public final class BoundedSpanProcessor implements SpanProcessor {
    private record Entry(Instant expiresAt,SpanData span) {}
    private final ArrayBlockingQueue<Entry> queue;
    private final SpanExporter exporter;
    private final Clock clock; private final Duration ttl;
    private final Map<String,Counter> dropped=new HashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object exportLock=new Object();
    private final Object queueLock=new Object();
    private final Semaphore slots;
    private final int capacity;
    private final Thread worker;
    public BoundedSpanProcessor(SpanExporter exporter,MeterRegistry meters,String destination,int capacity,Duration ttl,Clock clock,boolean startWorker) {
        if(capacity<1 || capacity>1000 || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(60))>0
                || !Set.of("otlp","langfuse").contains(destination)) throw new IllegalArgumentException("invalid telemetry queue configuration");
        this.exporter=exporter;this.clock=clock;this.ttl=ttl;this.capacity=capacity;
        queue=new ArrayBlockingQueue<>(capacity);slots=new Semaphore(capacity);
        for(String reason:List.of("FULL","EXPIRED","EXPORT_FAILED","SHUTDOWN"))
            dropped.put(reason,Counter.builder("copilot.telemetry.dropped").tags("destination",destination,"reason",reason).register(meters));
        Gauge.builder("copilot.telemetry.queue.size",queue,Collection::size).tag("destination",destination).register(meters);
        worker=new Thread(this::consume,"copilot-telemetry-"+destination);worker.setDaemon(true);
        if(startWorker) worker.start();
    }
    @Override public void onStart(Context parent,ReadWriteSpan span) {}
    @Override public boolean isStartRequired(){return false;}
    @Override public boolean isEndRequired(){return true;}
    @Override public void onEnd(ReadableSpan span) {
        synchronized(queueLock) {
            if(closed.get()) {drop("SHUTDOWN",1);return;}
            if(!slots.tryAcquire()) {drop("FULL",1);return;}
            try {queue.add(new Entry(clock.instant().plus(ttl),SafeSpanData.copy(span.toSpanData())));}
            catch(RuntimeException ignored) {slots.release();drop("EXPORT_FAILED",1);}
        }
    }
    int queued(){return queue.size();}
    private void consume() {
        while(!closed.get()) {
            try {Thread.sleep(100);drainOnce();}
            catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(RuntimeException ignored){/* exporter failures cannot escape into business processing */}
        }
    }
    void drainOnce() {
        synchronized(exportLock) {
            var batch=new ArrayList<SpanData>(64);Entry entry;Instant now=clock.instant();
            synchronized(queueLock) {
                for(int i=0;i<64 && (entry=queue.poll())!=null;i++) {
                    if(!now.isBefore(entry.expiresAt())) {drop("EXPIRED",1);slots.release();}else batch.add(entry.span());
                }
            }
            if(batch.isEmpty()) return;
            try {
                var result=exporter.export(batch);
                result.whenComplete(()->slots.release(batch.size()));
                result.join(2,TimeUnit.SECONDS);
                if(!result.isSuccess()) drop("EXPORT_FAILED",batch.size());
            } catch(RuntimeException e){slots.release(batch.size());drop("EXPORT_FAILED",batch.size());}
        }
    }
    private void drop(String reason,int count){dropped.get(reason).increment(count);}
    @Override public CompletableResultCode forceFlush(){return slots.availablePermits()==capacity?CompletableResultCode.ofSuccess():CompletableResultCode.ofFailure();}
    @Override public CompletableResultCode shutdown() {
        if(closed.compareAndSet(false,true)) {
            worker.interrupt();
            synchronized(queueLock) {int count=queue.size();queue.clear();slots.release(count);drop("SHUTDOWN",count);}
            try {return exporter.shutdown();} catch(RuntimeException ignored){return CompletableResultCode.ofFailure();}
        }
        return CompletableResultCode.ofSuccess();
    }
}
