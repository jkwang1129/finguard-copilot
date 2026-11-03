package com.finguard.copilot.observability;

import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalysisTelemetryTest {
    @Test void brokenMeterAndTracerDoNotReplaceBusinessResultsOrExceptions() {
        var meters=new SimpleMeterRegistry();
        meters.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter(){
            @Override public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id){throw new IllegalStateException("SECRET");}
        });
        var t=new AnalysisTelemetry(io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"),meters);
        assertThat(t.call(AnalysisTelemetry.Stage.MODEL,()->42)).isEqualTo(42);
        assertThatCode(()->t.providerAttempt("deepseek")).doesNotThrowAnyException();
        var original=new IllegalArgumentException("business");
        assertThatThrownBy(()->t.call(AnalysisTelemetry.Stage.MODEL,()->{throw original;})).isSameAs(original);
        var tracer=org.mockito.Mockito.mock(io.opentelemetry.api.trace.Tracer.class);
        org.mockito.Mockito.when(tracer.spanBuilder(org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("SECRET"));
        assertThat(new AnalysisTelemetry(tracer,null).call(AnalysisTelemetry.Stage.MODEL,()->7)).isEqualTo(7);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }
    @Test void noProviderAttemptIsAnExplicitZero() {
        var meters=new SimpleMeterRegistry();
        new AnalysisTelemetry(io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"),meters);
        assertThat(meters.get("copilot.provider.request.attempts").tag("provider","deepseek").counter().count()).isZero();
    }
    @Test void linksAsyncStagesWithoutBaggageAndRestoresThread() {
        var exporter=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry=new AnalysisTelemetry(provider.get("test"),new SimpleMeterRegistry());
            var id=UUID.randomUUID(); String parent;
            try(var api=telemetry.start(AnalysisTelemetry.Stage.API_CREATE,id,null)) {
                parent=telemetry.traceparent();
                assertThat(parent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
                telemetry.call(AnalysisTelemetry.Stage.CORE_CONTEXT,()->"ok");
            }
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
            try(var worker=telemetry.start(AnalysisTelemetry.Stage.WORKER,id,parent)) {
                assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(parent.substring(3,35));
            }
            var spans=exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(3);
            assertThat(spans.get(2).getParentSpanId()).isEqualTo(parent.substring(36,52));
        }
    }

    @Test void rejectsInvalidOrOversizedContextWithoutRejectingBusiness() {
        for(String parent:new String[]{null,"secret-token","00-"+"0".repeat(32)+"-"+"1".repeat(16)+"-01","x".repeat(10000)}) {
            assertThat(AnalysisTelemetry.validTraceparent(parent)).isNull();
            assertThat(AnalysisTelemetry.noop().call(AnalysisTelemetry.Stage.MODEL,()->42)).isEqualTo(42);
        }
    }

    @Test void controlledErrorHasNoExceptionBodyAndMetricsHaveNoIdentifiers() {
        var exporter=InMemorySpanExporter.create(); var metrics=new SimpleMeterRegistry();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var t=new AnalysisTelemetry(provider.get("test"),metrics);
            assertThatThrownBy(()->t.call(AnalysisTelemetry.Stage.MODEL,()->{throw new IllegalStateException("Bearer SECRET");}))
                    .isInstanceOf(IllegalStateException.class);
            var span=exporter.getFinishedSpanItems().get(0);
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getStatus().getDescription()).isEmpty();
            assertThat(span.getAttributes().toString()).doesNotContain("SECRET");
            assertThat(metrics.getMeters()).allSatisfy(m->assertThat(m.getId().getTags()).allSatisfy(tag->
                    assertThat(tag.getKey()).isIn("stage","outcome","provider")));
        }
    }
}
