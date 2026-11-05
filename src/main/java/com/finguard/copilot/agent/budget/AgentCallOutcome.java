package com.finguard.copilot.agent.budget;
import com.finguard.copilot.model.port.ModelUsage;
public record AgentCallOutcome(State state,ModelUsage usage) {
    public enum State { SUCCESS, FAILURE, UNKNOWN, NOT_SENT }
    public AgentCallOutcome {
        if(state==null || (state==State.SUCCESS || state==State.FAILURE) && usage==null
            || (state==State.UNKNOWN || state==State.NOT_SENT) && usage!=null) throw new IllegalArgumentException("Agent outcome invalid");
    }
    public static AgentCallOutcome success(ModelUsage usage){return new AgentCallOutcome(State.SUCCESS,usage);}
    public static AgentCallOutcome failure(ModelUsage usage){return new AgentCallOutcome(State.FAILURE,usage);}
    public static AgentCallOutcome unknown(){return new AgentCallOutcome(State.UNKNOWN,null);}
    public static AgentCallOutcome notSent(){return new AgentCallOutcome(State.NOT_SENT,null);}
}
