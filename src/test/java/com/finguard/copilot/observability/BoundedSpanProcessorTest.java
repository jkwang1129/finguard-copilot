package com.finguard.copilot.observability;

import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BoundedSpanProcessorTest {
    @Test void capacityIncludesInflightExportAndDoesNotBlockProducer() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var pending=new CompletableResultCode();
        var exporter=new Exporter(){@Override public CompletableResultCode export(Collection<SpanData> v){entered.countDown();return pending;}};
        var meters=new SimpleMeterRegistry();var p=new BoundedSpanProcessor(exporter,meters,"otlp",1,Duration.ofSeconds(60),Clock.systemUTC(),false);
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(p).build()) {
            provider.get("test").spanBuilder("one").startSpan().end();
            var thread=new Thread(p::drainOnce);thread.start();assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                thread.join(3000); // The export remains unresolved beyond the configured join timeout.
                assertThat(thread.isAlive()).isFalse();
                provider.get("test").spanBuilder("two").startSpan().end();
                assertThat(p.queued()).isZero();assertThat(p.forceFlush().isSuccess()).isFalse();
                assertThat(meters.get("copilot.telemetry.dropped").tags("destination","otlp","reason","FULL").counter().count()).isEqualTo(1);
            } finally {pending.succeed();thread.join(3000);}
        }
    }
    static class Exporter implements SpanExporter {
        List<SpanData> received=new ArrayList<>(); boolean fails;
        public CompletableResultCode export(Collection<SpanData> values){received.addAll(values);return fails?CompletableResultCode.ofFailure():CompletableResultCode.ofSuccess();}
        public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
        public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-02T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return now;}
    }
    @Test void capsQueueAndExpiresRatherThanRetainingOldData() {
        var clock=new MutableClock();var meters=new SimpleMeterRegistry();var exporter=new Exporter();
        var processor=new BoundedSpanProcessor(exporter,meters,"otlp",2,Duration.ofSeconds(60),clock,false);
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            for(int i=0;i<3;i++) provider.get("test").spanBuilder("SECRET").startSpan().end();
            assertThat(processor.queued()).isEqualTo(2);
            assertThat(meters.get("copilot.telemetry.dropped").tags("destination","otlp","reason","FULL").counter().count()).isEqualTo(1);
            clock.now=clock.now.plusSeconds(61); processor.drainOnce();
            assertThat(exporter.received).isEmpty(); assertThat(processor.queued()).isZero();
            assertThat(meters.get("copilot.telemetry.dropped").tags("destination","otlp","reason","EXPIRED").counter().count()).isEqualTo(2);
        }
    }
    @Test void exportsOnlySanitizedSnapshotsAndDoesNotRetryFailure() {
        var exporter=new Exporter();exporter.fails=true;var meters=new SimpleMeterRegistry();
        var processor=new BoundedSpanProcessor(exporter,meters,"langfuse",1000,Duration.ofSeconds(60),Clock.systemUTC(),false);
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            var span=provider.get("SECRET").spanBuilder("SECRET").startSpan();
            span.setAttribute("prompt","SECRET");span.end();processor.drainOnce();processor.drainOnce();
            assertThat(exporter.received).hasSize(1);
            assertThat(exporter.received.get(0).getName()).doesNotContain("SECRET");
            assertThat(meters.get("copilot.telemetry.dropped").tags("destination","langfuse","reason","EXPORT_FAILED").counter().count()).isEqualTo(1);
        }
    }
}
