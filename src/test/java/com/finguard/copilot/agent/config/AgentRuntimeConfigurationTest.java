package com.finguard.copilot.agent.config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.governance.GovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class AgentRuntimeConfigurationTest {
    final CopilotProperties properties=mock(CopilotProperties.class);
    @Test void runtimeCannotBeEnabledWithoutCaptureOrExplicitAggregateBudget(){
        when(properties.model()).thenReturn(CopilotProperties.Model.defaults());
        var config=new AgentRuntimeConfiguration();
        assertThatThrownBy(()->config.runtimeSettings(properties,GovernanceProperties.defaults(),new MockEnvironment()))
            .hasMessageContaining("capture");
        var env=new MockEnvironment().withProperty("finguard.copilot.agent.enabled","true");
        assertThatThrownBy(()->config.runtimeSettings(properties,GovernanceProperties.defaults(),env))
            .hasMessageContaining("budget");
        env.withProperty("finguard.copilot.agent.max-analysis-cny","0.10");
        assertThat(config.runtimeSettings(properties,GovernanceProperties.defaults(),env).maxAnalysisCny()).isEqualByComparingTo("0.10");
    }
}
