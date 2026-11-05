package com.finguard.copilot.agent.graph;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import java.util.*;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.dispatch.*;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.observability.AnalysisTelemetry;

/** Framework routes the real branches; each completed node commits the sole MySQL checkpoint. */
public final class ReviewAgentGraph {
    private AgentFaultHooks faults=AgentFaultHooks.none();
    public ReviewAgentGraph faults(AgentFaultHooks value){faults=Objects.requireNonNull(value);return this;}
    private final BoundedAgentRunner runner;private final CheckpointStore checkpoints;
    private final boolean humanGate;private final AnalysisTelemetry telemetry;private final CompiledGraph graph;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public ReviewAgentGraph(BoundedAgentRunner runner,CheckpointStore checkpoints,boolean humanGate,AnalysisTelemetry telemetry){
        this.runner=runner;this.checkpoints=checkpoints;this.humanGate=humanGate;this.telemetry=telemetry;
        try{
            var definition=new StateGraph();
            Map<String,String> routes=new HashMap<>();
            for(var node:AgentNode.values())routes.put(node.name(),node==AgentNode.DONE?StateGraph.END:node.name());
            routes.put("STOP",StateGraph.END);
            for(var node:AgentNode.values())if(node!=AgentNode.DONE){
                definition.addNode(node.name(),node_async(state->advance(state)));
                definition.addConditionalEdges(node.name(),edge_async(this::route),routes);
            }
            definition.addConditionalEdges(StateGraph.START,edge_async(this::route),routes);
            graph=definition.compile(CompileConfig.builder().recursionLimit(16).releaseThread(true)
                .saverConfig(SaverConfig.builder().build()).build());
        }catch(Exception exception){throw new IllegalStateException("Agent graph compilation failed",exception);}
    }
    public boolean handles(UUID id){return checkpoints.handles(id);}
    public AnalysisWorker.WorkerOutcome process(AnalysisRequestedMessage message){
        if(checkpoints.hasReceipt(message.eventId()))return AnalysisWorker.WorkerOutcome.NOOP;
        String owner=UUID.randomUUID().toString();Optional<AgentCheckpoint> acquired;
        try{acquired=checkpoints.acquire(message.analysisId(),owner);}
        catch(CopilotException invalid){
            checkpoints.failUninitialized(message.analysisId(),invalid.code().name(),message.eventId());
            return AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL;
        }
        if(acquired.isEmpty()){
            checkpoints.acknowledgeTerminal(message.analysisId(),message.eventId());
            return AnalysisWorker.WorkerOutcome.NOOP;
        }
        var cp=acquired.get();
        try{
            graph.invoke(Map.of("id",message.analysisId().toString(),"owner",owner,"revision",cp.revision(),
                "work",CanonicalJson.encode(cp.working()),"approved",cp.humanApproved(),"paused",false,"delivery",message.eventId().toString(),
                "parent",Objects.requireNonNullElse(telemetry.traceparent(),Objects.requireNonNullElse(message.traceparent(),""))));
            return AnalysisWorker.WorkerOutcome.COMPLETED;
        }catch(RuntimeException thrown){
            Throwable cause=thrown;for(int n=0;n<8 && cause.getCause()!=null;n++)cause=cause.getCause();
            if(cause instanceof AgentExecutionFence.Lost)return AnalysisWorker.WorkerOutcome.NOOP;
            String code=cause instanceof CopilotException controlled?
                controlled.code()==ErrorCode.CONFLICT?"AGENT_STEP_UNCERTAIN":controlled.code().name():"AGENT_STEP_UNCERTAIN";
            checkpoints.failOwned(message.analysisId(),owner,code,message.eventId());
            return AnalysisWorker.WorkerOutcome.FAILED_GUARDRAIL;
        }
    }
    private Map<String,Object> advance(OverAllState state){
        UUID id=UUID.fromString(state.<String>value("id").orElseThrow());
        String owner=state.<String>value("owner").orElseThrow();
        long revision=((Number)state.value("revision").orElseThrow()).longValue();
        AgentWorkingState previous=working(state);
        try(var scope=telemetry.start(AnalysisTelemetry.Stage.AGENT_GRAPH,id,state.<String>value("parent").orElse(""))){
        try{
        AgentWorkingState next=runner.step(id,previous,new AgentExecutionFence(owner,revision));
        boolean paused=humanGate && next.node()==AgentNode.TOOLS && previous.node()==AgentNode.PLAN
            && !state.<Boolean>value("approved").orElse(false);
        UUID delivery=UUID.fromString(state.<String>value("delivery").orElseThrow());
        boolean advanced=telemetry.call(AnalysisTelemetry.Stage.CHECKPOINT,()->checkpoints.compareAndAdvance(id,revision,owner,next,paused,delivery));
        if(!advanced)throw new AgentExecutionFence.Lost();
        faults.hit(AgentFaultHooks.Point.CHECKPOINT_AFTER_COMMIT,id);
        return Map.of("revision",revision+1,"work",CanonicalJson.encode(next),"paused",paused);
        }catch(RuntimeException failure){
            if(failure instanceof AgentExecutionFence.Lost)scope.outcome("NOOP");
            else scope.failure(failure instanceof CopilotException controlled?controlled.code().name():"AGENT_STEP_UNCERTAIN");
            throw failure;
        }
        }
    }
    private String route(OverAllState state){
        return state.<Boolean>value("paused").orElse(false)?"STOP":working(state).node().name();
    }
    private AgentWorkingState working(OverAllState state){
        try{return json.readValue(state.<String>value("work").orElseThrow(),AgentWorkingState.class);}
        catch(java.io.IOException|IllegalArgumentException exception){throw new CopilotException(ErrorCode.AGENT_CHECKPOINT_INVALID,"Agent graph state invalid");}
    }
}
