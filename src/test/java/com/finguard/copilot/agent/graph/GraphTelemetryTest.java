package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.dispatch.*;
import com.finguard.copilot.observability.AnalysisTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;
class GraphTelemetryTest {
    @Test void rejectedNodeExportsFailureAndControlledCodeInsteadOfSuccessfulSpan(){
        var exporter=InMemorySpanExporter.create();
        try(var tracer=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
            UUID id=UUID.randomUUID();var runner=mock(BoundedAgentRunner.class);var checkpoints=mock(CheckpointStore.class);
            when(runner.step(eq(id),any(),any(AgentExecutionFence.class))).thenThrow(new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"private"));
            when(checkpoints.acquire(eq(id),anyString())).thenReturn(Optional.of(new AgentCheckpoint(id,1,"agent-v1",0,"RETRIEVE",AgentWorkingState.initial(),"owner",Instant.now().plusSeconds(120),false,false,null,"a".repeat(64),"frozen",Instant.now().plusSeconds(3600),0,0)));
            var graph=new ReviewAgentGraph(runner,checkpoints,false,new AnalysisTelemetry(tracer.get("test"),null));
            assertThat(graph.process(new AnalysisRequestedMessage(1,UUID.randomUUID(),id,Instant.now()))).isEqualTo(AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL);
            var span=exporter.getFinishedSpanItems().get(0);
            assertThat(span.getAttributes().get(AttributeKey.stringKey("finguard.outcome"))).isEqualTo("FAILURE");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("finguard.error_code"))).isEqualTo("FACT_VALIDATION_FAILED");
            assertThat(span.getAttributes().toString()).doesNotContain("private");
        }
    }
}
