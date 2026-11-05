package com.finguard.copilot.agent.model;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.model.port.ModelUsage;
public final class AgentProtocolException extends CopilotException {
    private final ModelUsage usage;
    public AgentProtocolException(ErrorCode code,ModelUsage usage){super(code,"Agent model protocol rejected");this.usage=usage;}
    public ModelUsage verifiedUsage(){return usage;}
}
