package com.finguard.copilot.agent.graph;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.UUID;
import com.finguard.copilot.agent.checkpoint.CheckpointStore;
import com.finguard.copilot.analysis.application.AnalysisOrchestrator;
import com.finguard.copilot.analysis.persistence.AnalysisRunRepository;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.dispatch.*;
import com.finguard.copilot.guardrail.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
class AgentDispatchGuardTest {
    @Test void disablingRuntimeNeverFallsBackToLegacyModelForADurableGraphRun(){
        var legacy=mock(AnalysisOrchestrator.class);
        var checkpoints=mock(CheckpointStore.class);
        var worker=new AnalysisWorker(mock(AnalysisRunRepository.class),new SnapshotCodec(),legacy,
            new AnalysisOutputSchemaValidator(),new FactConsistencyValidator(),mock(TransactionTemplate.class),
            mock(RabbitTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper(),mock(CopilotProperties.class));
        worker.setAgentCheckpoints(checkpoints);
        var message=new AnalysisRequestedMessage(1,UUID.randomUUID(),UUID.randomUUID(),Instant.now());
        when(checkpoints.requiresGraph(message.analysisId())).thenReturn(true);
        worker.process(message,1);
        verify(checkpoints).deferDisabled(message);
        verifyNoInteractions(legacy);
    }
}
