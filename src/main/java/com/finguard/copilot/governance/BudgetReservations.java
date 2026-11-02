package com.finguard.copilot.governance;

import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.model.port.ModelUsage;
import com.finguard.copilot.usage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Money and the existing V6 call slot are admitted in one database transaction. */
public final class BudgetReservations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate admissionTx;
    private final ModelCallReservations calls;
    private final Clock clock;
    private static final ObjectMapper JSON=new ObjectMapper();
    public BudgetReservations(JdbcTemplate jdbc,TransactionTemplate tx,ModelCallReservations calls,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.calls=calls;this.clock=clock;
        this.admissionTx=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        this.admissionTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.admissionTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public record FrozenCall(String manifestHash,JsonNode manifest,ModelCostCalculator.PriceTable price,JsonNode limits,String provider) {}
    public FrozenCall frozen(UUID id) {
        var row=jdbc.queryForMap("SELECT manifest_sha256,manifest_json FROM model_run_manifest WHERE analysis_id=?",bytes(id));
        var manifest=parse((String)row.get("manifest_json"));
        if(!CanonicalJson.hash(manifest).equals(row.get("manifest_sha256"))) throw RunManifestService.rejected("frozen manifest checksum mismatch");
        JsonNode price=artifact("price",manifest.path("price"));
        JsonNode limits=artifact("budget",manifest.path("budget"));
        return new FrozenCall((String)row.get("manifest_sha256"),manifest,new ModelCostCalculator.PriceTable(
                manifest.path("price").path("version").asText(),price.path("currency").asText(),decimal(price,"inputPerMillion"),decimal(price,"outputPerMillion")),limits,price.path("provider").asText());
    }
    private JsonNode artifact(String kind,JsonNode binding) {
        var row=jdbc.queryForMap("SELECT content_sha256,content_json FROM model_versioned_artifact WHERE artifact_kind=? AND version_id=?",kind,binding.path("version").asText());
        var content=parse((String)row.get("content_json"));
        if(!CanonicalJson.hash(content).equals(binding.path("sha256").asText()) || !binding.path("sha256").asText().equals(row.get("content_sha256")))
            throw RunManifestService.rejected("frozen artifact checksum mismatch");
        return content;
    }
    public boolean hasReservation(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM model_budget_reservation WHERE analysis_id=?",Integer.class,bytes(id))==1;
    }
    public void recordProviderEvidence(com.finguard.copilot.model.port.ProviderCallEvidence evidence) {
        UUID id=UUID.fromString(evidence.analysisId());
        tx.executeWithoutResult(status->{
            if(!hasReservation(id) || !frozen(id).provider().equals(evidence.provider())) throw RunManifestService.rejected("provider observation has no matching admitted call");
            String details=CanonicalJson.encode(evidence);
            jdbc.update("INSERT IGNORE INTO model_budget_event(event_id,analysis_id,event_type,details_json) VALUES(?,?,'PROVIDER_RESPONSE',?)",
                    id+":provider:"+CanonicalJson.sha256(details),bytes(id),details);
        });
    }
    public boolean acquire(UUID id) {
        return Boolean.TRUE.equals(admissionTx.execute(status->{
            // Shared lock with Worker pre-admission rejection: a rejected/terminal
            // run cannot acquire a call after the failure decision is committed.
            String runState=jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=? FOR UPDATE",String.class,bytes(id));
            if(calls.reservedAt(id).isPresent()) return false;
            if("FAILED".equals(runState) || "SUCCEEDED".equals(runState)) throw new AnalysisAdmissionClosedException();
            if(!calls.reserve(id,clock.instant())) return false;
            FrozenCall call=frozen(id);
            JsonNode manifest=call.manifest();
            BigDecimal reserve=decimal(manifest,"reservedEstimateCny");
            BigDecimal calculated=cost(call.price(),manifest.path("inputUpperBound").asLong(),manifest.path("outputCap").asLong());
            if(!reserve.equals(calculated) || !"CNY".equals(call.price().currency())) throw RunManifestService.rejected("reservation price mismatch");
            String day=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC).toString();
            String provider=artifact("price",manifest.path("price")).path("provider").asText();
            var scopes=new TreeMap<String,BigDecimal>();
            scopes.put("analysis:"+id,decimal(call.limits(),"maxCallCny"));
            scopes.put("global:"+day,decimal(call.limits(),"maxGlobalDailyCny"));
            scopes.put("provider:"+provider+":"+day,decimal(call.limits(),"maxProviderDailyCny"));
            scopes.put("reviewer:"+manifest.path("reviewerKey").asText()+":"+day,decimal(call.limits(),"maxReviewerDailyCny"));
            scopes.forEach((key,limit)->{
                if(limit.signum()<=0) throw RunManifestService.rejected("budget limit must be positive");
                jdbc.update("INSERT IGNORE INTO model_budget_account(scope_key,currency,limit_cny) VALUES(?,'CNY',?)",key,limit);
                var account=jdbc.queryForMap("SELECT limit_cny,held_cny,settled_cny FROM model_budget_account WHERE scope_key=? FOR UPDATE",key);
                if(limit.compareTo((BigDecimal)account.get("limit_cny"))!=0) throw RunManifestService.rejected("active budget limit version conflict");
                if(((BigDecimal)account.get("held_cny")).add((BigDecimal)account.get("settled_cny")).add(reserve).compareTo(limit)>0)
                    throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"model budget exhausted");
            });
            jdbc.update("INSERT INTO model_budget_reservation(analysis_id,manifest_sha256,status,reserved_cny) VALUES(?,?,'RESERVED',?)",bytes(id),call.manifestHash(),reserve);
            scopes.forEach((key,limit)->{
                jdbc.update("UPDATE model_budget_account SET held_cny=held_cny+? WHERE scope_key=?",reserve,key);
                jdbc.update("INSERT INTO model_budget_allocation(analysis_id,scope_key) VALUES(?,?)",bytes(id),key);
            });
            event(id,"RESERVED",Map.of("reservedCny",reserve.toPlainString()));return true;
        }));
    }
    public boolean settle(UUID id,ModelUsage usage) {
        return Boolean.TRUE.equals(tx.execute(status->{
            var row=lock(id);String state=(String)row.get("status");
            if("SETTLED".equals(state)) {
                if(row.get("input_tokens")==null || row.get("output_tokens")==null) return false;
                if(((Number)row.get("input_tokens")).longValue()!=usage.inputTokens() || ((Number)row.get("output_tokens")).longValue()!=usage.outputTokens())
                    throw RunManifestService.rejected("settlement conflict");
                return true;
            }
            FrozenCall call=frozen(id);
            if(!"RESERVED".equals(state)) {
                if("UNKNOWN_HELD".equals(state)) observe(id,usage,call,(BigDecimal)row.get("reserved_cny"));
                return false;
            }
            if(usage.inputTokens()<=0 || usage.inputTokens()>call.manifest().path("inputUpperBound").asLong() ||
                    usage.outputTokens()>call.manifest().path("outputCap").asLong()) {
                unknownLocked(id,"USAGE_UNVERIFIED_OR_OUT_OF_BOUND");observe(id,usage,call,(BigDecimal)row.get("reserved_cny"));return false;
            }
            BigDecimal amount=cost(call.price(),usage.inputTokens(),usage.outputTokens());
            BigDecimal reserved=(BigDecimal)row.get("reserved_cny");
            if(amount.compareTo(reserved)>0) {unknownLocked(id,"COST_EXCEEDS_RESERVATION");observe(id,usage,call,reserved);return false;}
            updateAccounts(id,reserved,amount);
            jdbc.update("UPDATE model_budget_reservation SET status='SETTLED',settled_cny=?,input_tokens=?,output_tokens=?,updated_at=? WHERE analysis_id=?",
                    amount,usage.inputTokens(),usage.outputTokens(),Timestamp.from(clock.instant()),bytes(id));
            event(id,"SETTLED",Map.of("settledCny",amount.toPlainString(),"inputTokens",usage.inputTokens(),"outputTokens",usage.outputTokens()));return true;
        }));
    }
    public void markUnknown(UUID id,String reason) {
        markUnknown(id,reason,null);
    }
    public void markUnknown(UUID id,String reason,ModelUsage observed) {
        tx.executeWithoutResult(status->{
            if(!hasReservation(id)) return;
            var row=lock(id);
            if("RESERVED".equals(row.get("status"))) unknownLocked(id,reason);
            if(observed!=null) observe(id,observed,frozen(id),(BigDecimal)row.get("reserved_cny"));
        });
    }
    private void observe(UUID id,ModelUsage usage,FrozenCall call,BigDecimal reserved) {
        var details=new LinkedHashMap<String,Object>();
        details.put("classification","ACCOUNTING_OBSERVATION");details.put("appliedToBalance",false);
        details.put("usageKnown",usage.inputTokens()>0);
        details.put("reportedInputTokens",usage.inputTokens());details.put("reportedOutputTokens",usage.outputTokens());
        details.put("latencyMillis",usage.latency().toMillis());
        details.put("inputUpperBound",call.manifest().path("inputUpperBound").asLong());details.put("outputCap",call.manifest().path("outputCap").asLong());
        details.put("reservedCny",reserved.toPlainString());details.put("priceTableVersion",call.price().version());
        if(usage.inputTokens()>0) {
            var estimate=cost(call.price(),usage.inputTokens(),usage.outputTokens());
            details.put("observedEstimateCny",estimate.toPlainString());details.put("differenceCny",estimate.subtract(reserved).toPlainString());
        }
        String evidence=CanonicalJson.encode(details);
        jdbc.update("INSERT IGNORE INTO model_budget_event(event_id,analysis_id,event_type,details_json) VALUES(?,?,'OBSERVED_USAGE',?)",
                id+":usage:"+CanonicalJson.sha256(evidence),bytes(id),evidence);
    }
    private void unknownLocked(UUID id,String reason) {
        jdbc.update("UPDATE model_budget_reservation SET status='UNKNOWN_HELD',updated_at=? WHERE analysis_id=?",Timestamp.from(clock.instant()),bytes(id));
        event(id,"UNKNOWN_HELD",Map.of("reason",reason));
    }
    public void releaseNotSent(UUID id) {
        tx.executeWithoutResult(status->{
            var row=lock(id);
            if("RELEASED_NOT_SENT".equals(row.get("status"))) return;
            if(!"RESERVED".equals(row.get("status"))) throw RunManifestService.rejected("cannot release uncertain model call");
            updateAccounts(id,(BigDecimal)row.get("reserved_cny"),BigDecimal.ZERO);
            jdbc.update("UPDATE model_budget_reservation SET status='RELEASED_NOT_SENT',updated_at=? WHERE analysis_id=?",Timestamp.from(clock.instant()),bytes(id));
            event(id,"RELEASED_NOT_SENT",Map.of("proof","pre-send-permit-rejected"));
        });
    }
    /** Trusted offline reconciliation port only; no reviewer/public HTTP endpoint. */
    public void reconcileConfirmedCost(UUID id,String sourceReference,BigDecimal confirmedCny) {
        if(sourceReference==null || sourceReference.isBlank() || sourceReference.length()>256 || confirmedCny==null ||
                confirmedCny.signum()<0 || confirmedCny.scale()>8 || confirmedCny.precision()>20)
            throw RunManifestService.rejected("invalid confirmed reconciliation");
        String referenceHash=CanonicalJson.sha256(sourceReference);
        String eventId=id+":reconcile:"+referenceHash;
        tx.executeWithoutResult(status->{
            var row=lock(id);
            var prior=jdbc.queryForList("SELECT details_json FROM model_budget_event WHERE event_id=?",String.class,eventId);
            if(!prior.isEmpty()) {
                if(decimal(parse(prior.get(0)),"confirmedCny").compareTo(confirmedCny)!=0)
                    throw RunManifestService.rejected("reconciliation conflict");
                return;
            }
            String state=(String)row.get("status");
            if(!"UNKNOWN_HELD".equals(state) && !"SETTLED".equals(state)) throw RunManifestService.rejected("reconciliation requires an uncertain or settled call");
            BigDecimal old="SETTLED".equals(state)?(BigDecimal)row.get("settled_cny"):BigDecimal.ZERO;
            BigDecimal release="UNKNOWN_HELD".equals(state)?(BigDecimal)row.get("reserved_cny"):BigDecimal.ZERO;
            // A confirmed bill may exceed the earlier estimate. Preserve that fact;
            // future admission sees the excess and refuses further spending.
            updateAccounts(id,release,confirmedCny.subtract(old));
            jdbc.update("UPDATE model_budget_reservation SET status='SETTLED',settled_cny=?,updated_at=? WHERE analysis_id=?",
                    confirmedCny,Timestamp.from(clock.instant()),bytes(id));
            jdbc.update("INSERT INTO model_budget_event(event_id,analysis_id,event_type,details_json) VALUES(?,?,'RECONCILED',?)",
                    eventId,bytes(id),CanonicalJson.encode(Map.of("sourceReferenceSha256",referenceHash,"confirmedCny",confirmedCny.toPlainString(),
                            "previousSettledCny",old.toPlainString(),"adjustmentCny",confirmedCny.subtract(old).toPlainString())));
        });
    }
    private void updateAccounts(UUID id,BigDecimal release,BigDecimal settle) {
        List<String> keys=jdbc.queryForList("SELECT scope_key FROM model_budget_allocation WHERE analysis_id=? ORDER BY scope_key",String.class,bytes(id));
        for(String key:keys) jdbc.update("UPDATE model_budget_account SET held_cny=held_cny-?,settled_cny=settled_cny+? WHERE scope_key=?",release,settle,key);
    }
    private Map<String,Object> lock(UUID id) {return jdbc.queryForMap("SELECT * FROM model_budget_reservation WHERE analysis_id=? FOR UPDATE",bytes(id));}
    private void event(UUID id,String type,Object details) {
        jdbc.update("INSERT INTO model_budget_event(event_id,analysis_id,event_type,details_json) VALUES(?,?,?,?)",id+":"+type,bytes(id),type,CanonicalJson.encode(details));
    }
    public static BigDecimal cost(ModelCostCalculator.PriceTable price,long input,long output) {
        return price.inputPricePerMillion().multiply(BigDecimal.valueOf(input)).add(price.outputPricePerMillion().multiply(BigDecimal.valueOf(output)))
                .divide(new BigDecimal("1000000"),8,RoundingMode.CEILING);
    }
    private static BigDecimal decimal(JsonNode node,String field) {return new BigDecimal(node.path(field).asText());}
    private static JsonNode parse(String text) {
        try {return JSON.readTree(text);} catch(Exception e) {throw RunManifestService.rejected("frozen accounting data invalid");}
    }
    private static byte[] bytes(UUID id) {return JdbcAnalysisRunRepository.uuidBytes(id);}
}
