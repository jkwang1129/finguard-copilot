package com.finguard.copilot.agent.budget;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.usage.ModelCostCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AgentCallLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @Autowired public AgentCallLedger(JdbcTemplate jdbc,TransactionTemplate transactions){this(jdbc,transactions,Clock.systemUTC());}
    public AgentCallLedger(JdbcTemplate jdbc,TransactionTemplate transactions,Clock clock){
        this.jdbc=jdbc;this.clock=clock;this.tx=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public boolean isAgentRun(UUID id){return jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_policy WHERE analysis_id=?",Integer.class,bytes(id))==1;}
    public String reviewerKey(UUID id){
        String reviewer=jdbc.queryForObject("SELECT reviewer_username FROM analysis_run WHERE id=?",String.class,bytes(id));
        return CanonicalJson.sha256(reviewer);
    }
    public CallClaim acquire(UUID id,int ordinal,PreparedAgentCall call){
        return acquire(id,ordinal,call,null);
    }
    public CallClaim acquire(UUID id,int ordinal,PreparedAgentCall call,com.finguard.copilot.agent.runtime.AgentExecutionFence fence){
        if(id==null || ordinal<1 || ordinal>2) throw reject("Agent call ordinal exceeds limit");
        return Objects.requireNonNull(tx.execute(status->{
            var runs=jdbc.queryForList("SELECT status FROM analysis_run WHERE id=? FOR UPDATE",bytes(id));
            if(runs.size()!=1 || !isAgentRun(id) || Set.of("SUCCEEDED","FAILED","WAITING_FOR_HUMAN").contains(runs.get(0).get("status"))) throw reject("Agent run is not callable");
            com.finguard.copilot.agent.runtime.AgentExecutionFence.requireCurrent(jdbc,id,fence,clock);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_model_call WHERE analysis_id=? AND call_ordinal=?",Integer.class,bytes(id),ordinal)>0) throw reject("Agent call already claimed");
            if(ordinal==2){
                var previous=jdbc.queryForList("SELECT status,outcome_type FROM agent_model_call WHERE analysis_id=? AND call_ordinal=1",bytes(id));
                if(previous.size()!=1 || !"SETTLED".equals(previous.get(0).get("status"))
                    || !"SUCCESS".equals(previous.get(0).get("outcome_type"))) throw reject("Agent previous call is not settled successfully");
            }
            String frozenIndex=jdbc.queryForObject("SELECT index_version FROM agent_run_policy WHERE analysis_id=?",String.class,bytes(id));
            if(!Objects.equals(frozenIndex,call.indexVersion()) || !call.priceValidUntil().isAfter(clock.instant())) throw reject("Agent frozen policy or price expired");
            String policy=CanonicalJson.encode(call.policy()),policyHash=CanonicalJson.sha256(policy);
            jdbc.update("INSERT IGNORE INTO agent_model_policy(analysis_id,policy_sha256,policy_json) VALUES(?,?,?)",bytes(id),policyHash,policy);
            var existing=jdbc.queryForMap("SELECT policy_sha256,policy_json FROM agent_model_policy WHERE analysis_id=?",bytes(id));
            if(!policyHash.equals(existing.get("policy_sha256")) || !policyHash.equals(CanonicalJson.sha256((String)existing.get("policy_json")))) throw reject("Agent policy conflict");
            BigDecimal reserve=call.reserveCny();
            if(reserve.compareTo(call.maxCallCny())>0) throw budget();
            var scopes=new TreeMap<String,BigDecimal>();
            String day=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC).toString();
            scopes.put("agent-analysis:"+id,call.maxAnalysisCny());
            scopes.put("global:"+day,call.globalDailyCny());
            scopes.put("provider:"+call.provider()+":"+day,call.providerDailyCny());
            scopes.put("reviewer:"+reviewerKey(id)+":"+day,call.reviewerDailyCny());
            for(var scope:scopes.entrySet()){
                jdbc.update("INSERT IGNORE INTO model_budget_account(scope_key,currency,limit_cny) VALUES(?,'CNY',?)",scope.getKey(),scope.getValue());
                var row=jdbc.queryForMap("SELECT limit_cny,held_cny,settled_cny FROM model_budget_account WHERE scope_key=? FOR UPDATE",scope.getKey());
                if(((BigDecimal)row.get("limit_cny")).compareTo(scope.getValue())!=0) throw reject("Agent budget policy conflict");
                if(((BigDecimal)row.get("held_cny")).add((BigDecimal)row.get("settled_cny")).add(reserve).compareTo(scope.getValue())>0) throw budget();
            }
            String manifest=CanonicalJson.encode(call),hash=CanonicalJson.sha256(manifest);
            jdbc.update("INSERT INTO agent_model_call(analysis_id,call_ordinal,manifest_sha256,manifest_json,status,reserved_cny) VALUES(?,?,?,?,'RESERVED',?)",bytes(id),ordinal,hash,manifest,reserve);
            for(String scope:scopes.keySet()){
                jdbc.update("UPDATE model_budget_account SET held_cny=held_cny+? WHERE scope_key=?",reserve,scope);
                jdbc.update("INSERT INTO agent_model_budget_allocation(analysis_id,call_ordinal,scope_key) VALUES(?,?,?)",bytes(id),ordinal,scope);
            }
            event(id,ordinal,"RESERVED",hash);
            return new CallClaim(id,ordinal,hash);
        }));
    }
    public void recordOutcome(CallClaim claim,AgentCallOutcome outcome){
        recordOutcome(claim,outcome,null);
    }
    public void recordOutcome(CallClaim claim,AgentCallOutcome outcome,String providerHash){
        if(providerHash!=null && !providerHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid provider response hash");
        Objects.requireNonNull(claim);Objects.requireNonNull(outcome);
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=? FOR UPDATE",String.class,bytes(claim.analysisId()));
            var row=jdbc.queryForMap("SELECT * FROM agent_model_call WHERE analysis_id=? AND call_ordinal=? FOR UPDATE",bytes(claim.analysisId()),claim.callOrdinal());
            String manifest=(String)row.get("manifest_json");
            if(!claim.manifestHash().equals(row.get("manifest_sha256")) || !claim.manifestHash().equals(CanonicalJson.sha256(manifest))) throw reject("Agent claim checksum mismatch");
            String outcomeHash=CanonicalJson.hash(outcome);
            if(!"RESERVED".equals(row.get("status"))){
                if(outcomeHash.equals(row.get("outcome_sha256"))) return;
                throw reject("Agent outcome conflict");
            }
            PreparedAgentCall prepared;
            try{prepared=json.readValue(manifest,PreparedAgentCall.class);}catch(java.io.IOException exception){throw reject("Agent manifest invalid");}
            BigDecimal reserve=(BigDecimal)row.get("reserved_cny");
            BigDecimal settled=null;
            String next;
            if(outcome.state()==AgentCallOutcome.State.UNKNOWN){next="UNKNOWN_HELD";}
            else if(outcome.state()==AgentCallOutcome.State.NOT_SENT){next="RELEASED_NOT_SENT";}
            else {
                var usage=outcome.usage();
                if(usage.inputTokens()>prepared.inputUpperBound() || usage.outputTokens()>prepared.outputCap()) throw reject("Agent usage exceeds frozen bounds");
                settled=new ModelCostCalculator().estimate(usage,prepared.price()).amount();
                if(settled.compareTo(reserve)>0) throw reject("Agent settlement exceeds hold");
                next="SETTLED";
            }
            var allocations=jdbc.queryForList("SELECT scope_key FROM agent_model_budget_allocation WHERE analysis_id=? AND call_ordinal=? ORDER BY scope_key",String.class,bytes(claim.analysisId()),claim.callOrdinal());
            for(String scope:allocations){
                jdbc.queryForObject("SELECT held_cny FROM model_budget_account WHERE scope_key=? FOR UPDATE",BigDecimal.class,scope);
                if(!"UNKNOWN_HELD".equals(next)) jdbc.update("UPDATE model_budget_account SET held_cny=held_cny-?,settled_cny=settled_cny+? WHERE scope_key=?",reserve,settled==null?BigDecimal.ZERO:settled,scope);
            }
            var usage=outcome.usage();
            jdbc.update("""
                UPDATE agent_model_call SET status=?,settled_cny=?,input_tokens=?,output_tokens=?,latency_ms=?,
                outcome_sha256=?,outcome_type=?,updated_at=CURRENT_TIMESTAMP(6) WHERE analysis_id=? AND call_ordinal=?
                """,next,settled,usage==null?null:usage.inputTokens(),usage==null?null:usage.outputTokens(),
                usage==null?null:usage.latency().toMillis(),outcomeHash,outcome.state().name(),bytes(claim.analysisId()),claim.callOrdinal());
            event(claim.analysisId(),claim.callOrdinal(),next,outcomeHash);
            if(providerHash!=null) jdbc.update("UPDATE agent_model_call SET provider_response_sha256=? WHERE analysis_id=? AND call_ordinal=?",providerHash,bytes(claim.analysisId()),claim.callOrdinal());
        });
    }
    public record Totals(String accountingStatus,BigDecimal estimatedCost,BigDecimal heldCny,long inputTokens,long outputTokens,int calls){}
    public Totals totals(UUID id){
        var rows=jdbc.queryForList("SELECT status,reserved_cny,settled_cny,input_tokens,output_tokens FROM agent_model_call WHERE analysis_id=? ORDER BY call_ordinal",bytes(id));
        BigDecimal held=BigDecimal.ZERO,settled=BigDecimal.ZERO;long input=0,output=0;boolean unknown=false,reserved=false;
        for(var row:rows){
            String state=(String)row.get("status");
            if("RESERVED".equals(state) || "UNKNOWN_HELD".equals(state)) held=held.add((BigDecimal)row.get("reserved_cny"));
            unknown|="UNKNOWN_HELD".equals(state);reserved|="RESERVED".equals(state);
            if(row.get("settled_cny")!=null) settled=settled.add((BigDecimal)row.get("settled_cny"));
            if(row.get("input_tokens")!=null) input+=((Number)row.get("input_tokens")).longValue();
            if(row.get("output_tokens")!=null) output+=((Number)row.get("output_tokens")).longValue();
        }
        String state=rows.isEmpty()?"NOT_RECORDED":unknown?"UNKNOWN_HELD":reserved?"RESERVED":
            rows.stream().allMatch(row->"RELEASED_NOT_SENT".equals(row.get("status")))?"RELEASED_NOT_SENT":"SETTLED";
        return new Totals(state,rows.isEmpty()||unknown||reserved||"RELEASED_NOT_SENT".equals(state)?null:settled,held,input,output,rows.size());
    }
    private void event(UUID id,int ordinal,String type,String hash){jdbc.update("INSERT INTO agent_model_budget_event(analysis_id,call_ordinal,event_type,details_sha256) VALUES(?,?,?,?)",bytes(id),ordinal,type,hash);}
    private static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
    private static CopilotException reject(String message){return new CopilotException(ErrorCode.CONFLICT,message);}
    private static CopilotException budget(){return new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Agent model budget exhausted");}
}
