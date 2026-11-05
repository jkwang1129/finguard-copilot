package com.finguard.copilot.agent.runtime;
import com.finguard.copilot.analysis.domain.AnalysisResult;
public record AgentStepOutcome(Type type,String nextNode,AnalysisResult validatedResult,String failureCode) {
    public enum Type { ADVANCE, WAIT, FAIL }
    public static AgentStepOutcome succeeded(AnalysisResult result){return new AgentStepOutcome(Type.ADVANCE,"SUCCEEDED",result,null);}
    public static AgentStepOutcome failed(String code){return new AgentStepOutcome(Type.FAIL,"FAILED",null,code);}
    public static AgentStepOutcome waiting(){return new AgentStepOutcome(Type.WAIT,"WAITING_FOR_HUMAN",null,null);}
}
