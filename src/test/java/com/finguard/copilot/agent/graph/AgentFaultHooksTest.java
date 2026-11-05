package com.finguard.copilot.agent.graph;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class AgentFaultHooksTest {
    @Test void destructiveFaultHooksCannotEnableOutsideExactFixtureConfiguration(){
        assertThatThrownBy(()->new AgentFaultHooks("MODEL_AFTER_RESPONSE",true,false,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AgentFaultHooks("MODEL_AFTER_RESPONSE",false,true,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AgentFaultHooks("arbitrary-command",true,true,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->AgentFaultHooks.none().hit(AgentFaultHooks.Point.MODEL_AFTER_RESPONSE,java.util.UUID.randomUUID())).doesNotThrowAnyException();
    }
}
