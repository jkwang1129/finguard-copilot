package com.finguard.copilot.agent.graph;
import java.util.UUID;

/** Process fault injection is possible only with the exact zero-cost loopback fixture configuration. */
public final class AgentFaultHooks {
    public enum Point { TOOL_BEFORE_CLAIM,TOOL_AFTER_CLAIM,TOOL_BEFORE_READ,TOOL_AFTER_READ,TOOL_AFTER_PERSIST,
        MODEL_BEFORE_CLAIM,MODEL_AFTER_CLAIM,MODEL_AFTER_RESPONSE,MODEL_AFTER_PERSIST,CHECKPOINT_AFTER_COMMIT,BEFORE_ACK }
    private final Point selected;private final boolean probes;
    public AgentFaultHooks(String point,boolean fixture,boolean exactLoopback,boolean probes){
        boolean active=point!=null && !point.isBlank();
        if((active || probes) && (!fixture || !exactLoopback))throw new IllegalArgumentException("Fault probes require exact local fixture");
        selected=active?Point.valueOf(point):null;this.probes=probes;
    }
    public static AgentFaultHooks none(){return new AgentFaultHooks(null,false,false,false);}
    public void hit(Point point,UUID analysis){
        if(probes && point==Point.TOOL_BEFORE_READ)System.out.println("PHASE5_TOOL_READ "+analysis);
        if(point==selected){
            System.out.println("PHASE5_FAULT "+point+" "+analysis);System.out.flush();
            Runtime.getRuntime().halt(86);
        }
    }
}
