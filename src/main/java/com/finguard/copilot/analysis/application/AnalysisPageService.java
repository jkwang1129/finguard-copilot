package com.finguard.copilot.analysis.application;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finguard.copilot.analysis.domain.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.guardrail.AnalysisOutputSchemaValidator;
import com.finguard.copilot.observability.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Authorized UI projection. Exact numbers are strings; raw model/manifest content is never returned. */
@Service
public class AnalysisPageService {
    private final GetAnalysisService access;
    private final JdbcTemplate jdbc;
    private final AnalysisOutputSchemaValidator validator;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private com.finguard.copilot.agent.budget.AgentCallLedger agentLedger;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAgentLedger(com.finguard.copilot.agent.budget.AgentCallLedger value){this.agentLedger=value;}
    private com.finguard.copilot.agent.graph.AgentTraceService agentTraces;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAgentTraces(com.finguard.copilot.agent.graph.AgentTraceService value){this.agentTraces=value;}
    public AnalysisPageService(GetAnalysisService access,JdbcTemplate jdbc,AnalysisOutputSchemaValidator validator) {
        this.access=access;this.jdbc=jdbc;this.validator=validator;
    }
    public record Cost(String accountingStatus,String estimatedCost,String currency,String heldCny,
                       String inputTokens,String outputTokens,boolean callSlotClaimed) {}
    public record Versions(String manifestSha256,String modelFingerprint,String promptFingerprint,String indexFingerprint,
                           String priceFingerprint,String provider,String model) {}
    public record Page(int schemaVersion,UUID analysisId,String reviewTaskId,AnalysisStatus status,String failureCode,
                       ObjectNode result,Cost cost,Versions versions,String traceId,String feedbackVerdict,
                       com.finguard.copilot.agent.graph.AgentTraceService.Summary agent) {}

    public Page view(UUID id,String token) {
        var allowed=access.authorize(id,token);var run=allowed.view();byte[] key=JdbcAnalysisRunRepository.uuidBytes(id);
        var identity=one("SELECT review_task_id,trace_id FROM analysis_run WHERE id=?",key);
        var usage=one("SELECT provider,input_tokens,output_tokens,estimated_cost,currency FROM model_usage_ledger WHERE analysis_id=?",key);
        var reservation=one("SELECT status,reserved_cny,settled_cny,input_tokens,output_tokens FROM model_budget_reservation WHERE analysis_id=?",key);
        boolean claimed=jdbc.queryForObject("SELECT COUNT(*) FROM model_call_reservation WHERE analysis_id=?",Integer.class,key)>0;
        var manifest=one("SELECT manifest_sha256,manifest_json FROM model_run_manifest WHERE analysis_id=?",key);
        var feedback=one("SELECT verdict FROM review_feedback WHERE analysis_id=? AND BINARY reviewer_username=BINARY ?",key,allowed.reviewer());
        ObjectNode result=null;
        if(run.status()==AnalysisStatus.SUCCEEDED) {
            try {
                var parsed=validator.validate(json.readTree(run.resultJson()));
                if(!parsed.analysisId().equals(id)) throw new IllegalArgumentException();
                result=present(parsed,json);
            } catch(Exception ignored){throw new CopilotException(ErrorCode.INTERNAL_ERROR,"Validated analysis result is unavailable");}
        }
        Cost displayedCost=cost(usage,reservation,claimed);
        if(agentLedger!=null && agentLedger.isAgentRun(id)) {
            var totals=agentLedger.totals(id);
            if(totals.calls()>0) displayedCost=agentCost(totals);
        }
        return new Page(1,id,integer(identity.get("review_task_id")),run.status(),
                run.status()==AnalysisStatus.FAILED?SafeSpanData.errorCode(run.failureCode()):null,result,
                displayedCost,
                versions(manifest),safeHash(identity.get("trace_id"),32),
                feedback.isEmpty()?null:(String)feedback.get("verdict"),agentTraces==null?null:agentTraces.summaryAfterAuthorization(id));
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?Map.of():rows.get(0);
    }
    public static ObjectNode present(AnalysisResult result,ObjectMapper mapper) {
        ObjectNode node=mapper.valueToTree(result);
        for(JsonNode item:node.withArray("businessEvidence")) {
            var evidence=(ObjectNode)item;
            evidence.put("transactionId",item.get("transactionId").bigIntegerValue().toString());
            evidence.put("accountId",item.get("accountId").bigIntegerValue().toString());
            evidence.put("amount",item.get("amount").decimalValue().toPlainString());
        }
        return node;
    }
    public static Cost agentCost(com.finguard.copilot.agent.budget.AgentCallLedger.Totals totals) {
        boolean recorded=totals.calls()>0;
        boolean settled="SETTLED".equals(totals.accountingStatus());
        return new Cost(totals.accountingStatus(),totals.estimatedCost()==null?null:money(totals.estimatedCost()),
                recorded?"CNY":null,recorded?money(totals.heldCny()):null,
                settled?Long.toString(totals.inputTokens()):null,settled?Long.toString(totals.outputTokens()):null,recorded);
    }
    public static Cost cost(Map<String,Object> usage,Map<String,Object> reservation,boolean claimed) {
        if(!reservation.isEmpty()) {
            String status=(String)reservation.get("status");
            if(!Set.of("RESERVED","SETTLED","UNKNOWN_HELD","RELEASED_NOT_SENT").contains(status)) throw new IllegalStateException("Invalid accounting state");
            boolean settled=status.equals("SETTLED"),held=status.equals("RESERVED") || status.equals("UNKNOWN_HELD");
            return new Cost(status,settled?money(reservation.get("settled_cny")):null,"CNY",held?money(reservation.get("reserved_cny")):"0",
                    settled?integer(reservation.get("input_tokens")):null,settled?integer(reservation.get("output_tokens")):null,claimed);
        }
        return usage.isEmpty()?new Cost("NOT_RECORDED",null,null,null,null,null,claimed)
                :new Cost("LEGACY_ESTIMATE",money(usage.get("estimated_cost")),(String)usage.get("currency"),null,
                integer(usage.get("input_tokens")),integer(usage.get("output_tokens")),claimed);
    }
    private Versions versions(Map<String,Object> row) {
        if(row.isEmpty()) return null;
        try {
            var manifest=json.readTree((String)row.get("manifest_json"));
            String hash=(String)row.get("manifest_sha256");
            if(!CanonicalJson.hash(manifest).equals(hash)) throw new IllegalArgumentException();
            var model=one("SELECT content_json,content_sha256 FROM model_versioned_artifact WHERE artifact_kind='model' AND version_id=?",manifest.path("model").path("version").asText());
            var content=json.readTree((String)model.get("content_json"));
            if(!CanonicalJson.hash(content).equals(manifest.path("model").path("sha256").asText())
                    || !CanonicalJson.hash(content).equals(model.get("content_sha256"))) throw new IllegalArgumentException();
            return new Versions(hash,fingerprint(manifest,"model"),fingerprint(manifest,"prompt"),fingerprint(manifest,"index"),fingerprint(manifest,"price"),
                    TelemetryVocabulary.provider(content.path("provider").asText()),TelemetryVocabulary.model(content.path("modelId").asText()));
        } catch(Exception ignored){throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Frozen run versions are unavailable");}
    }
    private String fingerprint(JsonNode manifest,String key){return CanonicalJson.sha256(manifest.path(key).path("version").asText());}
    private String safeHash(Object value,int length){return value instanceof String s && s.matches("[0-9a-f]{"+length+"}")?s:null;}
    private static String money(Object value) {
        if(value==null) return null;
        if(value instanceof BigDecimal decimal) return decimal.toPlainString();
        throw new IllegalStateException("Invalid exact monetary value");
    }
    private static String integer(Object value){return value==null?null:value.toString();}
}
