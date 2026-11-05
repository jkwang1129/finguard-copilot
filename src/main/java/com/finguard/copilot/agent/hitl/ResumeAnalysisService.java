package com.finguard.copilot.agent.hitl;

import java.time.*;
import java.util.*;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.JdbcAgentRunContextLoader;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.*;
import com.finguard.copilot.governance.CanonicalJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResumeAnalysisService {
    private com.finguard.copilot.observability.AnalysisTelemetry telemetry=com.finguard.copilot.observability.AnalysisTelemetry.noop();
    @Autowired public void setTelemetry(com.finguard.copilot.observability.AnalysisTelemetry value){telemetry=Objects.requireNonNull(value);}
    private final GetAnalysisService access;private final CoreClient core;private final CheckpointStore store;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SnapshotCodec codec;private final Clock clock;
    @Autowired public ResumeAnalysisService(GetAnalysisService access,CoreClient core,CheckpointStore store,
            JdbcTemplate jdbc,TransactionTemplate tx,SnapshotCodec codec){this(access,core,store,jdbc,tx,codec,Clock.systemUTC());}
    public ResumeAnalysisService(GetAnalysisService access,CoreClient core,CheckpointStore store,
            JdbcTemplate jdbc,TransactionTemplate tx,SnapshotCodec codec,Clock clock){
        this.access=access;this.core=core;this.store=store;this.jdbc=jdbc;this.tx=tx;this.codec=codec;this.clock=clock;
    }
    public ResumeReceipt resume(UUID id,long expectedRevision,ResumeAction action,String key,String bearerToken){
        return telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.HUMAN_RESUME,()->{
            telemetry.analysisId(id);return resumeAuthorized(id,expectedRevision,action,key,bearerToken);
        });
    }
    private ResumeReceipt resumeAuthorized(UUID id,long expectedRevision,ResumeAction action,String key,String bearerToken){
        if(id==null || expectedRevision<0 || action==null || key==null || !key.matches("[!-~]{1,100}"))throw new CopilotException(ErrorCode.INVALID_REQUEST,"Invalid resume request");
        var authorized=access.authorize(id,bearerToken);
        String reviewer=authorized.reviewer(),hash=CanonicalJson.hash(Map.of("revision",expectedRevision,"action",action.name()));
        var replay=find(id,reviewer,key,hash);if(replay.isPresent())return replay.get();
        var checkpoint=store.read(id).orElseThrow(()->new CopilotException(ErrorCode.RESOURCE_NOT_FOUND,"Agent checkpoint not found"));
        if(!checkpoint.waiting() || checkpoint.revision()!=expectedRevision)throw conflict();
        if(!checkpoint.waitExpiresAt().isAfter(clock.instant()))throwAfterFailure(id,expectedRevision,ErrorCode.WAIT_EXPIRED);
        var identity=jdbc.queryForMap("SELECT review_task_id FROM analysis_run WHERE id=?",bytes(id));
        long task=((Number)identity.get("review_task_id")).longValue();
        boolean matches;
        try{
            var fresh=core.reviewTaskContext(task,bearerToken);
            var state=core.reviewTaskState(task,bearerToken);
            var original=new JdbcAgentRunContextLoader(jdbc,codec,clock).load(id).snapshot().facts();
            matches=fresh!=null && state!=null && fresh.reviewTask().id()==task && state.matches(fresh.reviewTask())
                && Set.of("PENDING","OPEN").contains(state.status())
                && java.security.MessageDigest.isEqual(semanticHash(original),semanticHash(fresh));
        }catch(CoreClientException e){throw mapped(e);}
        catch(CopilotException|IllegalArgumentException e){matches=false;}
        if(!matches)throwAfterFailure(id,expectedRevision,ErrorCode.CONTEXT_STALE);
        Decision decision=tx.execute(transaction->{
            store.lock(id);
            var repeated=find(id,reviewer,key,hash);if(repeated.isPresent())return new Decision(repeated.get(),null);
            var current=store.read(id).orElseThrow(ResumeAnalysisService::conflict);
            if(!current.waiting() || current.revision()!=expectedRevision)throw conflict();
            if(action==ResumeAction.CONTINUE && jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=? AND step_key LIKE 'mcp-%' AND status IN ('CLAIMED','UNKNOWN')",Integer.class,bytes(id))>0)throw conflict();
            if(!current.waitExpiresAt().isAfter(clock.instant())){
                store.finishFailure(id,"WAIT_EXPIRED");return new Decision(null,ErrorCode.WAIT_EXPIRED);
            }
            AnalysisStatus status;
            if(action==ResumeAction.CANCEL){store.finishFailure(id,"ANALYSIS_CANCELLED");status=AnalysisStatus.FAILED;}
            else{
                jdbc.update("""
                    UPDATE analysis_checkpoint SET revision=revision+1,waiting=FALSE,wait_expires_at=NULL,
                    owner_token=NULL,lease_until=NULL,human_approved=TRUE,updated_at=? WHERE analysis_id=?
                    """,java.sql.Timestamp.from(clock.instant()),bytes(id));
                status=AnalysisStatus.PLANNING;store.status(id,status,null);store.outbox(id,expectedRevision+1);
            }
            jdbc.update("""
                INSERT INTO analysis_resume_request(analysis_id,reviewer_username,idempotency_key,request_sha256,revision,action,response_status)
                VALUES(?,?,?,?,?,?,?)
                """,bytes(id),reviewer,key,hash,expectedRevision+1,action.name(),status.name());
            return new Decision(new ResumeReceipt(id,expectedRevision+1,status,false),null);
        });
        if(decision==null)throw conflict();
        if(decision.error()!=null)throw new CopilotException(decision.error(),"Agent wait expired");
        return decision.receipt();
    }
    private Optional<ResumeReceipt> find(UUID id,String reviewer,String key,String hash){
        var rows=jdbc.queryForList("SELECT request_sha256,revision,response_status FROM analysis_resume_request WHERE analysis_id=? AND BINARY reviewer_username=BINARY ? AND idempotency_key=?",bytes(id),reviewer,key);
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0);if(!hash.equals(row.get("request_sha256")))throw conflict();
        return Optional.of(new ResumeReceipt(id,((Number)row.get("revision")).longValue(),AnalysisStatus.valueOf((String)row.get("response_status")),true));
    }
    private void throwAfterFailure(UUID id,long revision,ErrorCode code){
        boolean changed=Boolean.TRUE.equals(tx.execute(transaction->{
            String run=store.lock(id);var current=store.read(id).orElseThrow(ResumeAnalysisService::conflict);
            if(!"WAITING_FOR_HUMAN".equals(run) || !current.waiting() || current.revision()!=revision)return false;
            store.finishFailure(id,code.name());return true;
        }));
        if(!changed)throw conflict();
        throw new CopilotException(code,"Agent captured context changed or wait expired");
    }
    private byte[] semanticHash(ReviewTaskContext facts){
        return codec.capture(new ReviewTaskContext(facts.reviewTask(),facts.transaction(),facts.reconciliation(),
            facts.riskFacts(),facts.accountSummary(),Instant.EPOCH)).sha256();
    }
    private static CopilotException mapped(CoreClientException e){
        return new CopilotException(switch(e.kind()){
            case UNAUTHENTICATED->ErrorCode.UNAUTHENTICATED;
            case FORBIDDEN->ErrorCode.FORBIDDEN;
            case NOT_FOUND->ErrorCode.RESOURCE_NOT_FOUND;
            default->ErrorCode.SERVICE_UNAVAILABLE;
        },"Core authorization or context unavailable");
    }
    private static CopilotException conflict(){return new CopilotException(ErrorCode.CONFLICT,"Agent resume conflict");}
    private static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
    private record Decision(ResumeReceipt receipt,ErrorCode error){}
}
