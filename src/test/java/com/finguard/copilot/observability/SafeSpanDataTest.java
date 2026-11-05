package com.finguard.copilot.observability;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SafeSpanDataTest {
    @Test void stripsAllFreeTextSurfacesAndOnlyKeepsValidatedAttributes() {
        var exporter=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().setResource(Resource.create(Attributes.builder().put("secret","SECRET").build()))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var span=provider.get("SECRET","SECRET").spanBuilder("SECRET").startSpan();
            span.setAttribute("finguard.stage","MODEL");
            span.setAttribute("finguard.analysis_id","SECRET");
            span.setAttribute("prompt","SECRET");
            span.setAttribute("finguard.tool_name","SECRET");
            span.setAttribute("gen_ai.request.model","SECRET");
            span.addEvent("SECRET"); span.recordException(new RuntimeException("SECRET"));
            span.setStatus(StatusCode.ERROR,"SECRET"); span.end();
            var safe=SafeSpanData.copy(exporter.getFinishedSpanItems().get(0));
            assertThat(safe.getName()).isEqualTo("copilot.model");
            assertThat(safe.getAttributes().toString()).doesNotContain("SECRET");
            assertThat(safe.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("finguard.tool_name"))).isEqualTo("OTHER");
            assertThat(safe.getEvents()).isEmpty(); assertThat(safe.getLinks()).isEmpty();
            assertThat(safe.getStatus().getDescription()).isEmpty();
            assertThat(safe.getResource().getAttributes().toString()).doesNotContain("SECRET");
            assertThat(safe.getInstrumentationScopeInfo().getName()).isEqualTo("finguard-copilot");
        }
    }
}
