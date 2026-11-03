package com.finguard.copilot.observability;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.dispatch.*;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.guardrail.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.usage.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerTelemetryTest {
    @Test void duplicateNoopDeliveryHasItsOwnSpanButNoModelAttempt() {
        var exporter=InMemorySpanExporter.create();var model=mock(AnalysisOrchestrator.class);
        when(model.requiresModelCall()).thenReturn(true);
        var worker=new AnalysisWorker(mock(AnalysisRunRepository.class),new SnapshotCodec(),model,
                new AnalysisOutputSchemaValidator(),new FactConsistencyValidator(),mock(TransactionTemplate.class),
                mock(RabbitTemplate.class),new ObjectMapper(),mock(CopilotProperties.class),mock(ModelUsageRecorder.class),mock(ModelCallReservations.class));
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry=new AnalysisTelemetry(provider.get("test"),new SimpleMeterRegistry());worker.setTelemetry(telemetry);
            var id=UUID.randomUUID();String parent;
            try(var api=telemetry.start(AnalysisTelemetry.Stage.API_CREATE,id,null)){parent=telemetry.traceparent();}
            var message=new AnalysisRequestedMessage(1,UUID.randomUUID(),id,Instant.now(),parent);
            assertThat(worker.process(message,1)).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
            assertThat(worker.process(message,1)).isEqualTo(AnalysisWorker.WorkerOutcome.NOOP);
            var spans=exporter.getFinishedSpanItems();assertThat(spans).hasSize(3);
            assertThat(spans.get(1).getTraceId()).isEqualTo(spans.get(0).getTraceId());
            assertThat(spans.get(1).getParentSpanId()).isEqualTo(spans.get(0).getSpanId());
            assertThat(spans.get(2).getSpanId()).isNotEqualTo(spans.get(1).getSpanId());
            verify(model,never()).execute(any(),any());
        }
    }
}
