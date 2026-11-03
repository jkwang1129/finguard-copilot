package com.finguard.copilot.analysis.application;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisPageServiceTest {
    @Test void presentationPreservesEighteenDecimalPlacesAndLongIdentifiers() {
        var result=new AnalysisResult(1,UUID.randomUUID(),"summary","rule",
                List.of(new AnalysisResult.BusinessEvidence(9007199254740993L,1,new BigDecimal("99.999999999999999999"),"USD","SYNTH_LIMIT","SYNTH",List.of("transaction.amount"))),
                List.of(),AnalysisRecommendation.INSUFFICIENT_EVIDENCE,"reason",List.of("policy"),List.of("demo"));
        var page=AnalysisPageService.present(result,new ObjectMapper());
        assertThat(page.path("businessEvidence").get(0).path("amount").textValue()).isEqualTo("99.999999999999999999");
        assertThat(page.path("businessEvidence").get(0).path("transactionId").textValue()).isEqualTo("9007199254740993");
    }
    @Test void unknownAndNotSentReservationsNeverClaimZeroActualCost() {
        for(String state:List.of("UNKNOWN_HELD","RESERVED","RELEASED_NOT_SENT")) {
            var cost=AnalysisPageService.cost(Map.of("estimated_cost",BigDecimal.ZERO,"currency","CNY"),Map.of("status",state,"reserved_cny",new BigDecimal("0.10")),true);
            assertThat(cost.estimatedCost()).isNull();assertThat(cost.accountingStatus()).isEqualTo(state);
        }
        var verified=AnalysisPageService.cost(Map.of(),Map.of("status","SETTLED","settled_cny",new BigDecimal("0.00000123"),"input_tokens",100L,"output_tokens",1L),true);
        assertThat(verified.estimatedCost()).isEqualTo("0.00000123");assertThat(verified.inputTokens()).isEqualTo("100");
    }
    @Test void projectionIsUnavailableBeforeAuthorizationAndDoesNotQueryUnscopedData() {
        var access=mock(GetAnalysisService.class);var jdbc=mock(JdbcTemplate.class);var id=UUID.randomUUID();
        when(access.authorize(id,"token")).thenThrow(new CopilotException(ErrorCode.RESOURCE_NOT_FOUND,"not found"));
        var service=new AnalysisPageService(access,jdbc,new AnalysisOutputSchemaValidator());
        assertThatThrownBy(()->service.view(id,"token")).isInstanceOf(CopilotException.class);verifyNoInteractions(jdbc);
    }
}
