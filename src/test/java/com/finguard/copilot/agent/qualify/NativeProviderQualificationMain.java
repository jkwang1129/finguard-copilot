package com.finguard.copilot.agent.qualify;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.finguard.copilot.agent.budget.*;
import com.finguard.copilot.agent.model.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.agent.tool.*;
import com.finguard.copilot.analysis.application.ModelAnalysisOrchestrator;
import com.finguard.copilot.analysis.domain.AnalysisResult;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.config.*;
import com.finguard.copilot.context.*;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.*;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.finguard.copilot.model.config.RealModelConfiguration;
import com.finguard.copilot.model.validation.StructuredAnalysisParser;
import org.flywaydb.core.Flyway;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.*;

/** Explicit CLI only, never a JUnit test. No provider HTTP without a durable claim. */
public final class NativeProviderQualificationMain {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final BigDecimal TWO=new BigDecimal("2.00");
    final JdbcTemplate jdbc;final TransactionTemplate tx;final AgentCallLedger ledger;final AgentCallAdmission admission;
    final AgentModelGateway gateway;final ToolExecutor tools;final BoundedAgentRunner runner;final ModelAnalysisOrchestrator validator;
    final CopilotProperties.Model model;final GovernanceProperties governance;final SnapshotCodec codec=new SnapshotCodec();
    final String batch,index;final Map<UUID,AgentRunContext> contexts=new HashMap<>();
    final Map<UUID,List<Map<String,Object>>> turnMetadata=new HashMap<>();
    int httpAttempts;
    NativeProviderQualificationMain(JdbcTemplate jdbc,TransactionTemplate tx,String batch,StringRedisTemplate redis){
        this.jdbc=jdbc;this.tx=tx;this.batch=batch;index="native-qualification-"+batch;
        model=model("deepseek-flash");governance=governance(new BigDecimal("0.10"));
        ledger=new AgentCallLedger(jdbc,tx);admission=new AgentCallAdmission(ledger,new RedisProviderPermits(redis,1,60),model.timeout());
        var provider=new CompatibleAgentModelGateway(model,RestClient.builder().requestFactory(RealModelConfiguration.modelRequestFactory(model.timeout())),new ToolRegistry());
        gateway=request->{
            String content=request.messages().get(1).path("content").asText();
            JsonNode quota;
            try{quota=JSON.readTree(content.split("\nEXECUTION_BUDGET\n",2)[1].split("\nCORE_FACTS\n",2)[0]);}
            catch(Exception invalid){throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Qualification execution quota unavailable");}
            var turn=new LinkedHashMap<String,Object>();turn.put("callOrdinal",request.callOrdinal());turn.put("allowTools",request.allowTools());turn.put("executionBudget",quota);
            turnMetadata.computeIfAbsent(request.analysisId(),unused->new ArrayList<>()).add(turn);
            httpAttempts++;var response=provider.complete(request);
            turn.put("selectedToolCalls",response instanceof AgentTurnResponse.ToolBatch selected?selected.calls().size():0);
            return response;
        };
        var retrieval=mock(KnowledgeRetrievalService.class);
        when(retrieval.retrievePinned(anyString(),anyString(),anyInt())).thenReturn(new KnowledgeRetrievalService.RetrievalBatch(index,List.of()));
        tools=new ToolExecutor(jdbc,tx,codec,retrieval,new ToolRegistry(),Clock.systemUTC());
        validator=new ModelAnalysisOrchestrator(request->{throw new IllegalStateException("Qualification validation cannot call a second model");},new ContextAssembler(TokenBudget.phase3Defaults(),TokenEstimator.conservative(),new PromptTemplate("review-analysis-v2")),new StructuredAnalysisParser(),model,CopilotProperties.Budget.defaults());
        runner=runner(model,governance);
        jdbc.update("INSERT INTO knowledge_index_version(index_version,status,published_at) VALUES(?,'PUBLISHED',CURRENT_TIMESTAMP(6))",index);
    }
    CopilotProperties.Model model(String name){return new CopilotProperties.Model("deepseek",name,URI.create("https://api.deepseek.com"),new SecretValue(required("COPILOT_MODEL_API_KEY")),"/chat/completions",32000,2000,Duration.ofSeconds(60),NativeReviewedPrice.VERSION,"CNY",new BigDecimal("2"),new BigDecimal("8"));}
    GovernanceProperties governance(BigDecimal limit){return new GovernanceProperties(limit,NativeReviewedPrice.FROM,NativeReviewedPrice.UNTIL,TWO,TWO,TWO,false);}
    BoundedAgentRunner runner(CopilotProperties.Model m,GovernanceProperties g){return new BoundedAgentRunner(gateway,tools,admission,ledger,contexts::get,validator,new AgentRuntimeSettings(m,g,new BigDecimal("0.20"),DeepseekNativeInputBound.VERSION));}
    AgentRunContext create(JsonNode spec){
        String caseId=spec.path("case_id").asText();UUID id=UUID.nameUUIDFromBytes((batch+":"+caseId).getBytes(StandardCharsets.UTF_8));
        var now=Instant.now();var facts=new ReviewTaskContext(new ReviewTaskContext.ReviewTask(42,0,"PENDING","LARGE_AMOUNT","REVIEW",now),new ReviewTaskContext.Transaction(2,3,new BigDecimal(spec.path("amount").asText()),"CNY",now,"CSV_IMPORT"),new ReviewTaskContext.Reconciliation("SUSPICIOUS","NONE","REVIEW"),List.of(),new ReviewTaskContext.AccountSummary("ACTIVE",spec.path("account").asText()),now);
        var snapshot=codec.capture(facts);byte[] key=bytes(id);
        jdbc.update("INSERT INTO analysis_run(id,status,reviewer_username,review_task_id,review_task_version,idempotency_key,request_hash) VALUES(?,'QUEUED','native-qualification-reviewer',42,0,?,?)",key,batch+":"+caseId,new byte[32]);
        jdbc.update("INSERT INTO analysis_input_snapshot(analysis_id,schema_version,facts_json,facts_sha256,captured_at,expires_at) VALUES(?,1,?,?,?,?)",key,new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8),snapshot.sha256(),java.sql.Timestamp.from(snapshot.capturedAt()),java.sql.Timestamp.from(snapshot.expiresAt()));
        jdbc.update("INSERT INTO agent_run_policy(analysis_id,workflow_version,snapshot_sha256,index_version,runtime_enabled) VALUES(?,'agent-v1',?,?,FALSE)",key,snapshot.sha256(),index);
        var context=new AgentRunContext(id,"native-qualification-reviewer",snapshot,index);contexts.put(id,context);return context;
    }
    ArrayNode messages(AgentRunContext context,JsonNode spec) throws Exception {
        var messages=JSON.createArrayNode();String schema;
        try(var input=getClass().getResourceAsStream("/schema/analysis-output-v1.schema.json")){schema=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        messages.addObject().put("role","system").put("content",new PromptTemplate("agent-review-v2").render()+"\nOUTPUT_SCHEMA\n"+schema);
        var requested=new ArrayList<String>();spec.path("tools").forEach(t->requested.add(t.asText()));
        String instruction=requested.isEmpty()?"Tools are unnecessary for this case. Return the final JSON directly.":"First invoke these supplied read-only tools: "+String.join(", ",requested)+". For search_review_policy use query risk review policy and topK1. Do not produce the final JSON before receiving tool results.";
        messages.addObject().put("role","user").put("content","Synthetic provider qualification. "+instruction+"\nanalysisId="+context.analysisId()+"\nEXECUTION_BUDGET\n"+CanonicalJson.encode(Map.of("toolCallsLimit",4,"toolCallsUsed",0,"toolCallsRemaining",4,"maxToolCallsThisTurn",4))+"\nCORE_FACTS\n"+CanonicalJson.encode(context.snapshot().facts())+"\nPOLICY_EVIDENCE\n[]\nPublished policy evidence is absent. Final recommendation must be INSUFFICIENT_EVIDENCE with concrete missing policy information, exact facts and no invented citations.");
        return messages;
    }
    AgentTurnResponse invoke(AgentRunContext context,AgentTurnRequest request,BoundedAgentRunner preparation){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call",Integer.class)>=24)throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Qualification request count exhausted");
        var prepared=preparation.prepare(context,request);var claim=admission.acquire(context.analysisId(),request.callOrdinal(),prepared);
        try{
            var response=gateway.complete(request);ledger.recordOutcome(claim,AgentCallOutcome.success(response.usage()),response.providerRequestHash());return response;
        }catch(AgentProtocolException error){
            try{ledger.recordOutcome(claim,error.verifiedUsage()==null?AgentCallOutcome.unknown():AgentCallOutcome.failure(error.verifiedUsage()));}
            catch(RuntimeException failure){holdUnknown(claim);}throw error;
        }catch(RuntimeException error){holdUnknown(claim);throw error;}
        finally{admission.close(claim);}
    }
    void holdUnknown(CallClaim claim){
        String state=jdbc.queryForObject("SELECT status FROM agent_model_call WHERE analysis_id=? AND call_ordinal=?",String.class,bytes(claim.analysisId()),claim.callOrdinal());
        if("RESERVED".equals(state))ledger.recordOutcome(claim,AgentCallOutcome.unknown());
    }
    Map<String,Object> runCase(JsonNode spec) throws Exception {
        var context=create(spec);UUID id=context.analysisId();String scenario=spec.path("scenario").asText();var row=new LinkedHashMap<String,Object>();row.put("case_id",spec.path("case_id").asText());row.put("analysisId",id.toString());row.put("scenario",scenario);
        int before=httpAttempts;boolean passed=false,roundTrip=false;String error=null;AnalysisResult validated=null;
        try{
            var initial=messages(context,spec);var request=new AgentTurnRequest(id,1,initial,true,2000);
            if(scenario.startsWith("reject_")){
                String expected=scenario.equals("reject_context")?"CONTEXT_BUDGET_EXCEEDED":scenario.equals("reject_budget")?"MODEL_BUDGET_EXCEEDED":"MODEL_CONFIGURATION_INVALID";
                try{
                    if(scenario.equals("reject_model"))runner(model("deepseek-v4-pro"),governance).prepare(context,request);
                    else if(scenario.equals("reject_unicode")){((ObjectNode)initial.get(1)).put("content",Character.toString((char)0xd800));runner.prepare(context,new AgentTurnRequest(id,1,initial,true,2000));}
                    else if(scenario.equals("reject_context")){((ObjectNode)initial.get(1)).put("content","x".repeat(32000));runner.prepare(context,new AgentTurnRequest(id,1,initial,true,2000));}
                    else{var bad=runner(model,governance(new BigDecimal("0.00000001")));invoke(context,request,bad);}
                }catch(CopilotException rejection){error=rejection.code().name();passed=expected.equals(error) && ledger.totals(id).calls()==0 && httpAttempts==before;}
            }else if(scenario.equals("runner")){
                // This path uses the actual bounded production runner. Count attempts from its durable claims.
                var outcome=runner.run(id);passed="SUCCEEDED".equals(outcome.nextNode());validated=outcome.validatedResult();error=outcome.failureCode();
            }else{
                var first=invoke(context,request,runner);
                if(first instanceof AgentTurnResponse.ToolBatch batch){
                    Set<String> requested=new HashSet<>();spec.path("tools").forEach(n->requested.add(n.asText()));
                    Set<String> selected=new HashSet<>();batch.calls().forEach(c->selected.add(c.toolName()));
                    if(!selected.containsAll(requested) || scenario.equals("direct"))throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"Qualification branch mismatch");
                    var calls=initial.addObject().put("role","assistant").putArray("tool_calls");
                    for(var call:batch.calls())calls.addObject().put("id",call.callId()).put("type","function").putObject("function").put("name",call.toolName()).put("arguments",CanonicalJson.encode(call.arguments()));
                    for(var call:batch.calls()){
                        var result=tools.execute(new ToolExecutionContext(id,context.reviewer(),HexFormat.of().formatHex(context.snapshot().sha256()),index,"qualification-"+call.callId()),new ToolInvocation(call.toolName(),call.arguments()));
                        initial.addObject().put("role","tool").put("tool_call_id",call.callId()).put("content",CanonicalJson.encode(result));
                    }
                    var user=(ObjectNode)initial.get(1);String content=user.path("content").asText();
                    int start=content.indexOf("\nEXECUTION_BUDGET\n")+"\nEXECUTION_BUDGET\n".length(),end=content.indexOf("\nCORE_FACTS\n");
                    user.put("content",content.substring(0,start)+CanonicalJson.encode(Map.of("toolCallsLimit",4,"toolCallsUsed",batch.calls().size(),"toolCallsRemaining",4-batch.calls().size(),"maxToolCallsThisTurn",0))+content.substring(end));
                    var second=invoke(context,new AgentTurnRequest(id,2,initial,false,2000),runner);
                    if(!(second instanceof AgentTurnResponse.FinalCandidate finalCandidate))throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"Qualification final candidate unavailable");
                    validated=validator.validateAgentCandidate(id,context.snapshot(),finalCandidate.content(),List.of());roundTrip=true;passed=true;
                }else if(first instanceof AgentTurnResponse.FinalCandidate finalCandidate){
                    if(!scenario.equals("direct"))throw new CopilotException(ErrorCode.MODEL_OUTPUT_INVALID,"Qualification tool branch not exercised");
                    validated=validator.validateAgentCandidate(id,context.snapshot(),finalCandidate.content(),List.of());passed=true;
                }
            }
        }catch(CopilotException failure){error=failure.code().name();}catch(RuntimeException failure){error="QUALIFICATION_INTERNAL_ERROR";}
        var totals=ledger.totals(id);
        var turns=turnMetadata.getOrDefault(id,List.of());
        boolean withinQuota=turns.stream().allMatch(turn->!turn.containsKey("selectedToolCalls") || (Integer)turn.get("selectedToolCalls")<=((JsonNode)turn.get("executionBudget")).path("maxToolCallsThisTurn").asInt());
        passed=passed && withinQuota;
        if(scenario.equals("runner"))roundTrip=passed && turns.size()==2 && turns.stream().anyMatch(turn->(Integer)turn.getOrDefault("selectedToolCalls",0)>0);
        if(validated!=null && passed)jdbc.update("INSERT INTO analysis_result(analysis_id,schema_version,recommendation,result_json) VALUES(?,1,?,?)",bytes(id),validated.recommendation().name(),CanonicalJson.encode(validated));
        jdbc.update("UPDATE analysis_run SET status=?,failure_code=? WHERE id=?",validated!=null && passed?"SUCCEEDED":"FAILED",error,bytes(id));
        row.put("passed",passed);row.put("failureCode",error);row.put("nativeToolRoundTrip",roundTrip);row.put("validatedResult",validated!=null && passed);
        row.put("modelClaims",totals.calls());row.put("providerAttempts",httpAttempts-before);row.put("inputTokens",totals.inputTokens());row.put("outputTokens",totals.outputTokens());row.put("estimatedCny",totals.estimatedCost()==null?null:totals.estimatedCost().toPlainString());row.put("heldCny",totals.heldCny().toPlainString());row.put("accountingStatus",totals.accountingStatus());
        row.put("tools",jdbc.queryForList("SELECT tool_name,status FROM analysis_tool_execution WHERE analysis_id=? ORDER BY slot",bytes(id)));
        row.put("turns",turns);row.put("selectedToolCallsWithinAdvertisedQuota",withinQuota);
        return row;
    }
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("NATIVE_QUALIFICATION_APPROVED")) || args.length!=2)throw new IllegalArgumentException("Explicit bounded qualification invocation required");
        Path data=Path.of(args[0]),output=Path.of(args[1]);String contents=Files.readString(data);
        var cases=validatedCases(contents,required("NATIVE_QUALIFICATION_BATCH"),required("NATIVE_QUALIFICATION_CASES_SHA256"));
        var ds=new DriverManagerDataSource(required("COPILOT_DB_URL"),required("COPILOT_DB_USERNAME"),required("COPILOT_DB_PASSWORD"));
        try(var connection=ds.getConnection()){if(!connection.getCatalog().matches("fg_p5nq_[0-9_]+"))throw new IllegalArgumentException("Qualification requires its own synthetic database");}
        Flyway.configure().dataSource(ds).load().migrate();var jdbc=new JdbcTemplate(ds);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run",Integer.class)!=0)throw new IllegalArgumentException("Qualification database already used; do not retry provider calls");
        var redisConfig=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(required("NATIVE_QUALIFICATION_REDIS_PORT")));redisConfig.setPassword(required("NATIVE_QUALIFICATION_REDIS_PASSWORD"));
        var connectionFactory=new LettuceConnectionFactory(redisConfig);connectionFactory.afterPropertiesSet();connectionFactory.start();
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();report.put("rows",rows);
        try{
            var template=new StringRedisTemplate(connectionFactory);template.afterPropertiesSet();
            var main=new NativeProviderQualificationMain(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),required("NATIVE_QUALIFICATION_BATCH"),template);
            report.put("batch",main.batch);report.put("javaVersion",System.getProperty("java.version"));report.put("model","deepseek-flash");report.put("advertisedModelVersion","DeepSeek-V4.1-Flash-1017");report.put("boundVersion",DeepseekNativeInputBound.VERSION);report.put("boundEvidenceSha256",DeepseekNativeInputBound.EVIDENCE_SHA256);report.put("priceVersion",NativeReviewedPrice.VERSION);report.put("datasetSha256",CanonicalJson.sha256(contents));report.put("scope","synthetic SQL/Redis/native adapter/tools/validator components; no Core API/UI E2E or human-quality claim");report.put("retrieval","deterministic empty published-index evidence fixture");
            for(var spec:cases){var row=main.runCase(spec);rows.add(row);System.out.println("QUALIFICATION "+spec.path("case_id").asText()+" passed="+row.get("passed")+" claims="+row.get("modelClaims"));if(Set.of("UNKNOWN_HELD","RESERVED").contains(row.get("accountingStatus"))){report.put("stoppedReason","UNKNOWN_COST_NO_RETRY");break;}}
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call",Integer.class);
            var spent=jdbc.queryForObject("SELECT COALESCE(SUM(settled_cny),0) FROM agent_model_call",BigDecimal.class);var held=jdbc.queryForObject("SELECT COALESCE(SUM(reserved_cny),0) FROM agent_model_call WHERE status IN ('RESERVED','UNKNOWN_HELD')",BigDecimal.class);
            boolean pass=rows.size()==12 && rows.stream().allMatch(r->Boolean.TRUE.equals(r.get("passed"))) && count<=24 && spent.add(held).compareTo(TWO)<=0 && held.signum()==0;
            report.put("status",pass?"QUALIFIED_SYNTHETIC_PROTOCOL":"NOT_QUALIFIED");report.put("providerClaims",count);report.put("knownEstimatedCny",spent.toPlainString());report.put("heldCny",held.toPlainString());report.put("budgetCeilingCny","2.00");report.put("maxRequests",24);report.put("automaticRetries",0);
        }finally{connectionFactory.destroy();Files.createDirectories(output.getParent());Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        System.out.println("QUALIFICATION_STATUS "+report.get("status"));if(!"QUALIFIED_SYNTHETIC_PROTOCOL".equals(report.get("status")))System.exit(2);
    }
    static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing qualification configuration: "+name);return value;}
    static List<JsonNode> validatedCases(String contents,String batch,String sha256) throws java.io.IOException {
        if(!CanonicalJson.sha256(contents).equals(sha256))throw new IllegalArgumentException("Frozen qualification cases changed");
        String prefix=switch(batch){case "phase5-native-20261104"->"NQ-";case "phase5-native-retest-20261104"->"RQ-";default->throw new IllegalArgumentException("Unknown qualification batch");};
        var cases=new ArrayList<JsonNode>();for(String line:contents.lines().toList())if(!line.isBlank())cases.add(JSON.readTree(line));
        if(cases.size()!=12)throw new IllegalArgumentException("Qualification needs exactly12 frozen cases");
        for(int i=0;i<12;i++)if(!String.format(prefix+"%03d",i+1).equals(cases.get(i).path("case_id").asText()))throw new IllegalArgumentException("Qualification case identity mismatch");
        return List.copyOf(cases);
    }
    static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
}
