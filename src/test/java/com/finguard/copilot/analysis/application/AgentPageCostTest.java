package com.finguard.copilot.analysis.application;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import com.finguard.copilot.agent.budget.AgentCallLedger.Totals;
import org.junit.jupiter.api.Test;
class AgentPageCostTest {
    @Test void aggregateProjectionKeepsUnknownCostsNullAndSettledAmountsExact(){
        var unknown=AnalysisPageService.agentCost(new Totals("UNKNOWN_HELD",null,new BigDecimal("0.01000000"),100,5,2));
        assertThat(unknown.estimatedCost()).isNull();assertThat(unknown.heldCny()).isEqualTo("0.01000000");
        assertThat(unknown.inputTokens()).isNull();assertThat(unknown.callSlotClaimed()).isTrue();
        var settled=AnalysisPageService.agentCost(new Totals("SETTLED",new BigDecimal("0.00000123"),BigDecimal.ZERO,200,10,2));
        assertThat(settled.estimatedCost()).isEqualTo("0.00000123");
        assertThat(settled.inputTokens()).isEqualTo("200");assertThat(settled.outputTokens()).isEqualTo("10");
        var absent=AnalysisPageService.agentCost(new Totals("NOT_RECORDED",null,BigDecimal.ZERO,0,0,0));
        assertThat(absent.currency()).isNull();assertThat(absent.heldCny()).isNull();
    }
}
