package com.finguard.copilot.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.PromptTemplate;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.knowledge.retrieval.*;
import com.finguard.copilot.usage.ModelCostCalculator;

/** Bounded orchestration only; durable graph ownership and human gates are added by P5-4. */
public final class BoundedAgentRunner {
    private final AgentModelGateway model;private final ToolExecutor tools;
    private final AgentCallAdmission admission;private final AgentCallLedger ledger;
    private final AgentRunContextLoader contexts;private final ModelAnalysisOrchestrator validator;
    private final AgentRuntimeSettings settings;
    private final java.time.Clock clock;
    private com.finguard.copilot.agent.graph.AgentFaultHooks faults=com.finguard.copilot.agent.graph.AgentFaultHooks.none();
    public BoundedAgentRunner faults(com.finguard.copilot.agent.graph.AgentFaultHooks value){faults=Objects.requireNonNull(value);return this;}
    private void fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point point,UUID id){faults.hit(point,id);}
    private com.finguard.copilot.observability.AnalysisTelemetry telemetry=com.finguard.copilot.observability.AnalysisTelemetry.noop();
    public BoundedAgentRunner telemetry(com.finguard.copilot.observability.AnalysisTelemetry value){telemetry=Objects.requireNonNull(value);return this;}
    private final ToolRegistry registry=new ToolRegistry();
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public BoundedAgentRunner(AgentModelGateway model,ToolExecutor tools,AgentCallAdmission admission,
            AgentCallLedger ledger,AgentRunContextLoader contexts,ModelAnalysisOrchestrator validator,AgentRuntimeSettings settings){
        this(model,tools,admission,ledger,contexts,validator,settings,java.time.Clock.systemUTC());
    }
    public BoundedAgentRunner(AgentModelGateway model,ToolExecutor tools,AgentCallAdmission admission,
            AgentCallLedger ledger,AgentRunContextLoader contexts,ModelAnalysisOrchestrator validator,AgentRuntimeSettings settings,java.time.Clock clock){
        this.model=model;this.tools=tools;this.admission=admission;this.ledger=ledger;
        this.contexts=contexts;this.validator=validator;this.settings=settings;
        this.clock=Objects.requireNonNull(clock);
    }
    public AgentStepOutcome run(UUID id){
        try {
            AgentWorkingState state=AgentWorkingState.initial();
            for(int step=0;step<12;step++){
                if(state.node()==AgentNode.DONE) return AgentStepOutcome.succeeded(state.validatedResult());
                state=step(id,state);
            }
            throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Agent step budget exhausted");
        }catch(CopilotException exception){return AgentStepOutcome.failed(exception.code().name());}
        catch(RuntimeException exception){return AgentStepOutcome.failed("ANALYSIS_WORKER_FAILED");}
    }
    /** One bounded node, suitable for a durable graph transaction before the next external action. */
    public AgentWorkingState step(UUID id,AgentWorkingState state){return step(id,state,null);}
    public AgentWorkingState step(UUID id,AgentWorkingState state,AgentExecutionFence fence){
        var context=contexts.load(id);
        try {
            switch(state.node()){
                case RETRIEVE -> {
                    if(state.retrievalAttempts()>=2) throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Agent retrieval budget exhausted");
                    String query=context.snapshot().facts().reviewTask().ruleCode()+" "+context.snapshot().facts().reviewTask().reasonCode();
                    var args=json.createObjectNode().put("query",query+(state.retrievalAttempts()==0?"":" review policy")).put("topK",5);
                    var result=executeTool(scope(context,"retrieval-"+state.retrievalAttempts(),fence),new ToolInvocation("search_review_policy",args));
                    var batch=json.treeToValue(result.data(),KnowledgeRetrievalService.RetrievalBatch.class);
                    if(!context.indexVersion().equals(batch.indexVersion())) throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"Agent evidence index mismatch");
                    var evidence=new ArrayList<>(state.evidence());evidence.addAll(batch.evidence());
                    int attempts=state.retrievalAttempts()+1;
                    return new AgentWorkingState(evidence.isEmpty() && attempts<2?AgentNode.RETRIEVE:AgentNode.PLAN,
                        evidence,List.of(),List.of(),attempts,state.toolsUsed()+1,null,null);
                }
                case PLAN -> {
                    var first=invoke(context,new AgentTurnRequest(id,1,initialMessages(context,state,true),true,settings.model().outputTokenLimit()),fence);
                    if(first instanceof AgentTurnResponse.ToolBatch batch){
                        if(batch.calls().isEmpty() || batch.calls().size()>4-state.toolsUsed()) throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Agent tool budget exhausted");
                        return state.planned(batch.calls());
                    }
                    var candidate=(AgentTurnResponse.FinalCandidate)first;
                    try{return state.candidate(validator.validateAgentCandidate(id,context.snapshot(),candidate.content(),state.evidence()));}
                    catch(CopilotException invalid){
                        if(invalid.code()!=ErrorCode.OUTPUT_SCHEMA_INVALID && invalid.code()!=ErrorCode.MODEL_OUTPUT_INVALID) throw invalid;
                        return new AgentWorkingState(AgentNode.REPAIR,state.evidence(),List.of(),List.of(),state.retrievalAttempts(),state.toolsUsed(),invalid.code().name(),null);
                    }
                }
                case TOOLS -> {
                    int index=state.toolResults().size();
                    if(index>=state.plannedTools().size() || state.toolsUsed()>=4) throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Agent tool budget exhausted");
                    var call=state.plannedTools().get(index);
                    var result=executeTool(scope(context,"plan-1-"+index,fence),new ToolInvocation(call.toolName(),call.arguments()));
                    var evidence=new ArrayList<>(state.evidence());
                    if("search_review_policy".equals(call.toolName())){
                        var batch=json.treeToValue(result.data(),KnowledgeRetrievalService.RetrievalBatch.class);
                        if(!context.indexVersion().equals(batch.indexVersion())) throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"Agent evidence index mismatch");
                        for(var item:batch.evidence()) if(evidence.stream().noneMatch(old->old.citation().chunkId().equals(item.citation().chunkId())))evidence.add(item);
                    }
                    var results=new ArrayList<>(state.toolResults());results.add(result);
                    return new AgentWorkingState(results.size()==state.plannedTools().size()?AgentNode.GENERATE:AgentNode.TOOLS,
                        evidence,state.plannedTools(),results,state.retrievalAttempts(),state.toolsUsed()+1,null,null);
                }
                case GENERATE,REPAIR -> {
                    var messages=initialMessages(context,state,false);
                    if(state.node()==AgentNode.REPAIR){
                        messages.addObject().put("role","user").put("content","The previous candidate failed "+state.repairCode()+". Return one complete valid final JSON. No tools.");
                    }else{
                        var callNodes=messages.addObject().put("role","assistant").putArray("tool_calls");
                        for(var call:state.plannedTools()){
                            callNodes.addObject().put("id",call.callId()).put("type","function").putObject("function")
                                .put("name",call.toolName()).put("arguments",CanonicalJson.encode(call.arguments()));
                        }
                        for(int n=0;n<state.toolResults().size();n++)messages.addObject().put("role","tool")
                            .put("tool_call_id",state.plannedTools().get(n).callId()).put("content",CanonicalJson.encode(state.toolResults().get(n)));
                    }
                    var response=invoke(context,new AgentTurnRequest(id,2,messages,false,settings.model().outputTokenLimit()),fence);
                    if(!(response instanceof AgentTurnResponse.FinalCandidate candidate))throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"Agent second turn requested tools");
                    return state.candidate(validator.validateAgentCandidate(id,context.snapshot(),candidate.content(),state.evidence()));
                }
                case COMPLETE -> {
                    if(state.validatedResult()==null) throw new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"Agent validated result unavailable");
                    validator.validateAgentCandidate(id,context.snapshot(),CanonicalJson.encode(state.validatedResult()),state.evidence());
                    return state.withNode(AgentNode.DONE);
                }
                case DONE -> {return state;}
            }
            throw new IllegalStateException("Agent node invalid");
        }catch(java.io.IOException exception){throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"Agent structured state invalid");}
    }
    private AgentTurnResponse invoke(AgentRunContext context,AgentTurnRequest request,AgentExecutionFence fence){
        var prepared=prepare(context,request);
        fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.MODEL_BEFORE_CLAIM,context.analysisId());
        CallClaim claim=fence==null?admission.acquire(context.analysisId(),request.callOrdinal(),prepared):admission.acquire(context.analysisId(),request.callOrdinal(),prepared,fence);
        fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.MODEL_AFTER_CLAIM,context.analysisId());
        try{
            AgentTurnResponse response;
            try{response=telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.MODEL,()->{
                telemetry.modelMetadata(settings.model().provider(),settings.model().modelId(),prepared.promptVersion(),prepared.modelVersion());
                telemetry.manifest(CanonicalJson.hash(prepared));telemetry.providerAttempt(settings.model().provider());
                return model.complete(request);
            });}
            catch(AgentProtocolException exception){
                if(exception.verifiedUsage()!=null) ledger.recordOutcome(claim,AgentCallOutcome.failure(exception.verifiedUsage()));
                else ledger.recordOutcome(claim,AgentCallOutcome.unknown());
                throw exception;
            }catch(RuntimeException exception){ledger.recordOutcome(claim,AgentCallOutcome.unknown());throw exception;}
            fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.MODEL_AFTER_RESPONSE,context.analysisId());
            ledger.recordOutcome(claim,AgentCallOutcome.success(response.usage()),response.providerRequestHash());
            fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.MODEL_AFTER_PERSIST,context.analysisId());
            return response;
        }finally{admission.close(claim);}
    }
    private ToolResult executeTool(ToolExecutionContext scope,ToolInvocation invocation){
        return tools.execute(scope,invocation);
    }
    public PreparedAgentCall prepare(AgentRunContext context,AgentTurnRequest request){
        var model=settings.model();var governed=settings.governance();
        boolean fixture=governed.fixtureMode() && DeepseekTextTokenBound.fixtureEndpoint(model);
        if(governed.fixtureMode() && !fixture || !fixture && !DeepseekNativeInputBound.VERSION.equals(settings.nativeBoundVersion()))throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Agent native protocol needs an explicit reviewed input bound");
        var payload=CompatibleAgentModelGateway.payload(model,request,registry);
        int upper=fixture?Math.addExact(payload.toString().getBytes(StandardCharsets.UTF_8).length,1024):new DeepseekNativeInputBound().upperBound(model,request,registry);
        NativeReviewedPrice.Window price=fixture?null:NativeReviewedPrice.validate(model,governed,clock);
        if(upper>32000 || upper>model.inputTokenLimit()) throw new CopilotException(ErrorCode.CONTEXT_BUDGET_EXCEEDED,"Agent wire context exceeded");
        if(governed.priceValidFrom().isAfter(clock.instant()) || !governed.priceValidUntil().isAfter(clock.instant())) throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Agent price expired");
        return new PreparedAgentCall(model.provider(),"agent-model-"+CanonicalJson.hash(Map.of("model",model.modelId(),"endpoint",model.baseUrl().toString(),"path",model.chatCompletionsPath())),
            "agent-review-v2:"+CanonicalJson.hash(request.messages().get(0)),CanonicalJson.hash(payload),context.indexVersion(),CanonicalJson.hash(registry.schemas()),upper,request.outputCap(),
            new ModelCostCalculator.PriceTable(model.priceTableVersion(),model.currency(),model.inputPricePerMillion(),model.outputPricePerMillion()),
            governed.maxCallCny(),settings.maxAnalysisCny(),governed.maxReviewerDailyCny(),governed.maxProviderDailyCny(),
            governed.maxGlobalDailyCny(),fixture?governed.priceValidUntil():price.until(),fixture,fixture?null:DeepseekNativeInputBound.VERSION,fixture?null:DeepseekNativeInputBound.EVIDENCE_SHA256,fixture?null:price.from());
    }
    private ArrayNode initialMessages(AgentRunContext context,AgentWorkingState state,boolean allowTools) throws java.io.IOException{
        var messages=json.createArrayNode();
        String schema;
        try(var source=getClass().getResourceAsStream("/schema/analysis-output-v1.schema.json")){
            if(source==null) throw new IllegalStateException("Output schema unavailable");
            schema=new String(source.readAllBytes(),StandardCharsets.UTF_8);
        }
        messages.addObject().put("role","system").put("content",new PromptTemplate("agent-review-v2").render()+"\nOUTPUT_SCHEMA\n"+schema);
        int remaining=4-state.toolsUsed();
        var budget=Map.of("toolCallsLimit",4,"toolCallsUsed",state.toolsUsed(),"toolCallsRemaining",remaining,
            "maxToolCallsThisTurn",allowTools?remaining:0);
        messages.addObject().put("role","user").put("content","analysisId="+context.analysisId()+"\nEXECUTION_BUDGET\n"+
            CanonicalJson.encode(budget)+"\nCORE_FACTS\n"+CanonicalJson.encode(context.snapshot().facts())+
            "\nPOLICY_EVIDENCE\n"+CanonicalJson.encode(state.evidence()));
        return messages;
    }
    private ToolExecutionContext scope(AgentRunContext context,String step,AgentExecutionFence fence){
        return new ToolExecutionContext(context.analysisId(),context.reviewer(),HexFormat.of().formatHex(context.snapshot().sha256()),context.indexVersion(),step,null,null,fence);
    }
}
