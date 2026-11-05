package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import com.finguard.copilot.agent.budget.AgentCallLedger;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.AgentWorkingState;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
class AgentTraceServiceTest {
    @Test void authorizationPrecedesAllTraceReads(){
        var access=mock(GetAnalysisService.class);var checkpoints=mock(CheckpointStore.class);var jdbc=mock(JdbcTemplate.class);
        UUID id=UUID.randomUUID();when(access.authorize(id,"bad")).thenThrow(new CopilotException(ErrorCode.FORBIDDEN,"denied"));
        var trace=new AgentTraceService(access,checkpoints,mock(AgentCallLedger.class),jdbc,new MockEnvironment());
        assertThatThrownBy(()->trace.view(id,"bad")).isInstanceOf(CopilotException.class);
        verifyNoInteractions(checkpoints,jdbc);
    }
    @Test void expiredSourceIsNotReplayableEvenBeforeCleanup(){
        UUID id=UUID.randomUUID();var checkpoints=mock(CheckpointStore.class);var ledger=mock(AgentCallLedger.class);var jdbc=mock(JdbcTemplate.class);
        when(checkpoints.read(id)).thenReturn(Optional.of(new AgentCheckpoint(id,1,"agent-v1",2,"TOOLS",AgentWorkingState.initial(),null,null,true,false,Instant.now().plusSeconds(60),"a".repeat(64),"frozen",Instant.now().minusSeconds(1),1,1)));
        when(jdbc.queryForMap(anyString(),any(Object[].class))).thenReturn(Map.of("fully_reproducible",true));
        when(jdbc.queryForObject(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class))).thenReturn(Instant.now().minusSeconds(86400).toString());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        when(ledger.totals(id)).thenReturn(new AgentCallLedger.Totals("SETTLED",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,10,5,1));
        var trace=new AgentTraceService(mock(GetAnalysisService.class),checkpoints,ledger,jdbc,new MockEnvironment().withProperty("finguard.copilot.agent.runtime-enabled","true"));
        assertThat(trace.summaryAfterAuthorization(id).replayable()).isFalse();
        assertThat(trace.summaryAfterAuthorization(id).canResume()).isFalse();
    }
}
