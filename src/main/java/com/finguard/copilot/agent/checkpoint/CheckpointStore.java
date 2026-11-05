package com.finguard.copilot.agent.checkpoint;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.dispatch.AnalysisRequestedMessage;
import com.finguard.copilot.events.AnalysisEventRepository;
import com.finguard.copilot.governance.CanonicalJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL owns workflow recovery, CAS, fencing, stage events and durable wakeups. */
@Repository
public class CheckpointStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AnalysisEventRepository events;
    private final SnapshotCodec codec;private final Clock clock;private final Duration lease;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    @Autowired public CheckpointStore(JdbcTemplate jdbc,TransactionTemplate tx,AnalysisEventRepository events,SnapshotCodec codec){
        this(jdbc,tx,events,codec,Clock.systemUTC(),Duration.ofMinutes(2));
    }
    public CheckpointStore(JdbcTemplate jdbc,TransactionTemplate tx,AnalysisEventRepository events,SnapshotCodec codec,Clock clock,Duration lease){
        this.jdbc=jdbc;this.tx=tx;this.events=events;this.codec=codec;this.clock=clock;this.lease=lease;
    }
    public boolean handles(UUID id){
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM agent_run_policy p WHERE analysis_id=? AND p.runtime_enabled=TRUE
            AND NOT EXISTS(SELECT 1 FROM model_call_reservation c WHERE c.analysis_id=p.analysis_id)
            """,Integer.class,bytes(id))==1;
    }
    public boolean requiresGraph(UUID id){
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM agent_run_policy p WHERE analysis_id=? AND
            (runtime_enabled=TRUE OR EXISTS(SELECT 1 FROM analysis_checkpoint c WHERE c.analysis_id=p.analysis_id)
             OR EXISTS(SELECT 1 FROM agent_model_call m WHERE m.analysis_id=p.analysis_id))
            """,Integer.class,bytes(id))>0;
    }
    public void deferDisabled(AnalysisRequestedMessage message){
        tx.executeWithoutResult(transaction->{
            UUID id=message.analysisId();String run=lock(id);
            if(run==null || hasReceipt(message.eventId()))return;
            if(!Set.of("SUCCEEDED","FAILED").contains(run) && requiresGraph(id)
                && jdbc.queryForObject("SELECT COUNT(*) FROM analysis_input_snapshot WHERE analysis_id=? AND facts_json IS NOT NULL AND deleted_at IS NULL AND expires_at>?",Integer.class,bytes(id),at(clock.instant()))!=1){
                finishFailure(id,"CONTEXT_STALE");receipt(id,message.eventId());return;
            }
            if(!Set.of("SUCCEEDED","FAILED","WAITING_FOR_HUMAN").contains(run)){
                var event=UUID.nameUUIDFromBytes(("agent-disabled:"+message.eventId()).getBytes(StandardCharsets.UTF_8));
                var payload=CanonicalJson.encode(new AnalysisRequestedMessage(1,event,id,clock.instant(),message.traceparent()));
                jdbc.update("""
                    INSERT IGNORE INTO outbox_event(id,analysis_id,event_type,schema_version,payload_json,attempt_count,next_attempt_at,created_at)
                    VALUES(?,?,'ANALYSIS_REQUESTED',1,?,0,?,?)
                    """,bytes(event),bytes(id),payload,at(clock.instant().plusSeconds(30)),at(clock.instant()));
            }
            receipt(id,message.eventId());
        });
    }
    public Optional<AgentCheckpoint> acquire(UUID id,String owner){
        return tx.execute(status->{
            String run=lock(id);
            if(run==null || Set.of("SUCCEEDED","FAILED","WAITING_FOR_HUMAN").contains(run)) return Optional.empty();
            if(!handles(id)){
                if(requiresGraph(id))throw new CopilotException(ErrorCode.MODEL_CONFIGURATION_INVALID,"Agent runtime policy conflicts with a legacy call");
                return Optional.empty();
            }
            var context=new JdbcAgentRunContextLoader(jdbc,codec,clock).load(id);
            var existing=read(id);
            if(existing.isPresent()){
                var cp=existing.get();
                if(cp.waiting() || cp.owner()!=null && cp.leaseUntil()!=null && cp.leaseUntil().isAfter(clock.instant())) return Optional.empty();
                jdbc.update("UPDATE analysis_checkpoint SET owner_token=?,lease_until=?,updated_at=? WHERE analysis_id=?",
                    owner,at(clock.instant().plus(lease)),at(clock.instant()),bytes(id));
                status(id,stage(cp.working().node()),null);
            }else{
                String memory=CanonicalJson.encode(AgentWorkingState.initial());
                jdbc.update("""
                    INSERT INTO analysis_checkpoint(analysis_id,schema_version,workflow_version,revision,next_node,memory_json,
                    memory_sha256,snapshot_sha256,index_version,owner_token,lease_until)
                    VALUES(?,1,'agent-v1',0,'RETRIEVE',?,?,?,?,?,?)
                    """,bytes(id),memory,CanonicalJson.sha256(memory),context.snapshot().sha256(),context.indexVersion(),owner,at(clock.instant().plus(lease)));
                status(id,AnalysisStatus.RETRIEVAL_READY,null);
            }
            return read(id);
        });
    }
    public Optional<AgentCheckpoint> read(UUID id){
        var rows=jdbc.query("""
            SELECT c.*,s.expires_at AS snapshot_expires_at,p.snapshot_sha256 AS policy_sha,p.index_version AS policy_index
            FROM analysis_checkpoint c JOIN agent_run_policy p ON c.analysis_id=p.analysis_id
            JOIN analysis_input_snapshot s ON s.analysis_id=c.analysis_id WHERE c.analysis_id=?
            """,(r,n)->{
                if(r.getInt("schema_version")!=1 || !"agent-v1".equals(r.getString("workflow_version"))
                    || !java.security.MessageDigest.isEqual(r.getBytes("snapshot_sha256"),r.getBytes("policy_sha"))
                    || !Objects.equals(r.getString("index_version"),r.getString("policy_index"))) throw invalid();
                String memory=r.getString("memory_json");AgentWorkingState working=null;
                if(memory!=null){
                    if(!CanonicalJson.sha256(memory).equals(r.getString("memory_sha256"))) throw invalid();
                    try{working=json.readValue(memory,AgentWorkingState.class);}catch(java.io.IOException|IllegalArgumentException e){throw invalid();}
                    if(!working.node().name().equals(r.getString("next_node"))) throw invalid();
                }
                return new AgentCheckpoint(id,1,"agent-v1",r.getLong("revision"),r.getString("next_node"),working,
                    r.getString("owner_token"),instant(r.getTimestamp("lease_until")),r.getBoolean("waiting"),r.getBoolean("human_approved"),
                    instant(r.getTimestamp("wait_expires_at")),HexFormat.of().formatHex(r.getBytes("snapshot_sha256")),
                    r.getString("index_version"),instant(r.getTimestamp("snapshot_expires_at")),r.getInt("model_calls"),r.getInt("tool_calls"));
            },bytes(id));
        return rows.stream().findFirst();
    }
    public boolean compareAndAdvance(UUID id,long expected,String owner,AgentWorkingState next,boolean waiting){
        return compareAndAdvance(id,expected,owner,next,waiting,null);
    }
    public boolean compareAndAdvance(UUID id,long expected,String owner,AgentWorkingState next,boolean waiting,UUID delivery){
        return Boolean.TRUE.equals(tx.execute(transaction->{
            String run=lock(id);var found=read(id);
            if(found.isEmpty() || Set.of("SUCCEEDED","FAILED").contains(run))return false;
            var cp=found.get();
            if(cp.revision()!=expected || !Objects.equals(cp.owner(),owner) || cp.waiting()
                || cp.leaseUntil()==null || !cp.leaseUntil().isAfter(clock.instant()) || cp.working()==null) return false;
            if(!cp.snapshotExpiresAt().isAfter(clock.instant())){
                finishFailure(id,"CONTEXT_STALE");if(delivery!=null)receipt(id,delivery);return false;
            }
            if(waiting && next.node()!=AgentNode.TOOLS)throw invalid();
            String memory=CanonicalJson.encode(next);
            if(memory.getBytes(StandardCharsets.UTF_8).length>131072)throw invalid();
            boolean done=next.node()==AgentNode.DONE;
            Instant deadline=waiting?Collections.min(List.of(clock.instant().plus(Duration.ofHours(24)),cp.snapshotExpiresAt())):null;
            int calls=count("agent_model_call",id),tools=count("analysis_tool_execution",id);
            jdbc.update("""
                UPDATE analysis_checkpoint SET revision=revision+1,next_node=?,memory_json=?,memory_sha256=?,
                owner_token=?,lease_until=?,waiting=?,wait_expires_at=?,model_calls=?,tool_calls=?,updated_at=?
                WHERE analysis_id=?
                """,next.node().name(),memory,CanonicalJson.sha256(memory),waiting||done?null:owner,
                waiting||done?null:at(clock.instant().plus(lease)),waiting,at(deadline),calls,tools,at(clock.instant()),bytes(id));
            if(done){
                if(next.validatedResult()==null || !id.equals(next.validatedResult().analysisId())) throw invalid();
                jdbc.update("INSERT INTO analysis_result(analysis_id,schema_version,recommendation,result_json) VALUES(?,1,?,?)",
                    bytes(id),next.validatedResult().recommendation().name(),CanonicalJson.encode(next.validatedResult()));
            }
            status(id,waiting?AnalysisStatus.WAITING_FOR_HUMAN:stage(next.node()),null);
            if(!waiting && !done)outbox(id,expected+1);
            if((waiting||done) && delivery!=null)receipt(id,delivery);
            return true;
        }));
    }
    public boolean failOwned(UUID id,String owner,String code,UUID delivery){
        return Boolean.TRUE.equals(tx.execute(transaction->{
            String run=lock(id);var found=ownership(id);
            if(found.isEmpty() || Set.of("SUCCEEDED","FAILED").contains(run) || !Objects.equals(owner,found.get().owner())
                || found.get().lease()==null || !found.get().lease().isAfter(clock.instant()) || found.get().waiting())return false;
            finishFailure(id,code);
            if(delivery!=null)receipt(id,delivery);
            return true;
        }));
    }
    public int expireWaiting(int limit){
        var ids=jdbc.queryForList("SELECT analysis_id FROM analysis_checkpoint WHERE waiting=TRUE AND wait_expires_at<=? ORDER BY wait_expires_at LIMIT ?",byte[].class,at(clock.instant()),limit);
        int changed=0;
        for(var key:ids)try{if(Boolean.TRUE.equals(tx.execute(transaction->{
            UUID id=uuid(key);lock(id);var cp=read(id).orElseThrow(CheckpointStore::invalid);
            if(!cp.waiting() || cp.waitExpiresAt().isAfter(clock.instant()))return false;
            finishFailure(id,"WAIT_EXPIRED");return true;
        })))changed++;}catch(CopilotException invalid){if(quarantineInvalid(uuid(key),invalid))changed++;}
        return changed;
    }
    public int requeueExpired(int limit){
        var ids=jdbc.queryForList("""
            SELECT c.analysis_id FROM analysis_checkpoint c JOIN analysis_run r ON c.analysis_id=r.id
            WHERE c.waiting=FALSE AND c.owner_token IS NOT NULL AND c.lease_until<=?
            AND r.status NOT IN ('SUCCEEDED','FAILED') ORDER BY c.lease_until LIMIT ?
            """,byte[].class,at(clock.instant()),limit);
        int changed=0;
        for(var key:ids)try{if(Boolean.TRUE.equals(tx.execute(transaction->{
            UUID id=uuid(key);String run=lock(id);var cp=read(id).orElseThrow(CheckpointStore::invalid);
            if(cp.waiting() || cp.owner()==null || cp.leaseUntil().isAfter(clock.instant()) || Set.of("SUCCEEDED","FAILED").contains(run))return false;
            if(!cp.snapshotExpiresAt().isAfter(clock.instant())){finishFailure(id,"CONTEXT_STALE");return true;}
            jdbc.update("UPDATE analysis_checkpoint SET owner_token=NULL,lease_until=NULL,revision=revision+1,updated_at=? WHERE analysis_id=?",at(clock.instant()),bytes(id));
            outbox(id,cp.revision()+1);return true;
        })))changed++;}catch(CopilotException invalid){if(quarantineInvalid(uuid(key),invalid))changed++;}
        return changed;
    }
    private boolean quarantineInvalid(UUID id,CopilotException failure){
        if(failure.code()!=ErrorCode.AGENT_CHECKPOINT_INVALID)throw failure;
        return Boolean.TRUE.equals(tx.execute(transaction->{
            String run=lock(id);if(run==null || Set.of("SUCCEEDED","FAILED").contains(run))return false;
            int expired=jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_checkpoint WHERE analysis_id=? AND
                ((waiting=TRUE AND wait_expires_at<=?) OR (waiting=FALSE AND owner_token IS NOT NULL AND lease_until<=?))
                """,Integer.class,bytes(id),at(clock.instant()),at(clock.instant()));
            if(expired==0)return false;
            finishFailure(id,"AGENT_CHECKPOINT_INVALID");return true;
        }));
    }
    public boolean hasReceipt(UUID event){return jdbc.queryForObject("SELECT COUNT(*) FROM analysis_message_receipt WHERE event_id=?",Integer.class,bytes(event))>0;}
    public void acknowledgeTerminal(UUID id,UUID event){
        tx.executeWithoutResult(transaction->{String run=lock(id);if(Set.of("SUCCEEDED","FAILED","WAITING_FOR_HUMAN").contains(run))receipt(id,event);});
    }
    public void failUninitialized(UUID id,String code,UUID event){
        tx.executeWithoutResult(transaction->{
            String run=lock(id);if(run==null || Set.of("SUCCEEDED","FAILED").contains(run))return;
            var existing=ownership(id);
            if(existing.isPresent() && existing.get().owner()!=null && existing.get().lease()!=null
                && existing.get().lease().isAfter(clock.instant()))return;
            finishFailure(id,code);receipt(id,event);
        });
    }
    public void outbox(UUID id,long revision){
        var event=UUID.nameUUIDFromBytes(("agent:"+id+":"+revision).getBytes(StandardCharsets.UTF_8));
        String payload=CanonicalJson.encode(new AnalysisRequestedMessage(1,event,id,clock.instant(),com.finguard.copilot.observability.AnalysisTelemetry.noop().traceparent()));
        jdbc.update("""
            INSERT IGNORE INTO outbox_event(id,analysis_id,event_type,schema_version,payload_json,attempt_count,next_attempt_at,created_at)
            VALUES(?,?,'ANALYSIS_REQUESTED',1,?,0,?,?)
            """,bytes(event),bytes(id),payload,at(clock.instant()),at(clock.instant()));
    }
    public void finishFailure(UUID id,String code){
        String unknown=com.finguard.copilot.governance.CanonicalJson.hash(com.finguard.copilot.agent.budget.AgentCallOutcome.unknown());
        for(Integer ordinal:jdbc.queryForList("SELECT call_ordinal FROM agent_model_call WHERE analysis_id=? AND status='RESERVED'",Integer.class,bytes(id))) {
            jdbc.update("UPDATE agent_model_call SET status='UNKNOWN_HELD',outcome_type='UNKNOWN',outcome_sha256=?,updated_at=? WHERE analysis_id=? AND call_ordinal=?",
                unknown,at(clock.instant()),bytes(id),ordinal);
            jdbc.update("INSERT IGNORE INTO agent_model_budget_event(analysis_id,call_ordinal,event_type,details_sha256) VALUES(?,?,'UNKNOWN_HELD',?)",bytes(id),ordinal,unknown);
        }
        jdbc.update("UPDATE analysis_tool_execution SET status='UNKNOWN',failure_code='TOOL_OUTCOME_UNKNOWN',completed_at=? WHERE analysis_id=? AND status='CLAIMED'",at(clock.instant()),bytes(id));
        jdbc.update("UPDATE analysis_checkpoint SET revision=revision+1,owner_token=NULL,lease_until=NULL,waiting=FALSE,wait_expires_at=NULL,updated_at=? WHERE analysis_id=?",at(clock.instant()),bytes(id));
        status(id,AnalysisStatus.FAILED,code);
    }
    public String lock(UUID id){
        var rows=jdbc.queryForList("SELECT status FROM analysis_run WHERE id=? FOR UPDATE",String.class,bytes(id));
        return rows.isEmpty()?null:rows.get(0);
    }
    public void status(UUID id,AnalysisStatus status,String failure){
        jdbc.update("UPDATE analysis_run SET status=?,failure_code=?,updated_at=? WHERE id=?",status.name(),failure,at(clock.instant()),bytes(id));
        events.append(id,status,failure,clock.instant(),"STATE_CHANGED");
    }
    private void receipt(UUID id,UUID event){jdbc.update("INSERT IGNORE INTO analysis_message_receipt(event_id,analysis_id,received_at) VALUES(?,?,?)",bytes(event),bytes(id),at(clock.instant()));}
    private record Ownership(String owner,Instant lease,boolean waiting){}
    private Optional<Ownership> ownership(UUID id){
        // Quarantine must not decode the very payload it is trying to reject.
        return jdbc.query("SELECT owner_token,lease_until,waiting FROM analysis_checkpoint WHERE analysis_id=?",
            (r,n)->new Ownership(r.getString(1),instant(r.getTimestamp(2)),r.getBoolean(3)),bytes(id)).stream().findFirst();
    }
    private int count(String table,UUID id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE analysis_id=?",Integer.class,bytes(id));}
    private static AnalysisStatus stage(AgentNode node){return switch(node){
        case RETRIEVE -> AnalysisStatus.RETRIEVAL_READY;
        case PLAN -> AnalysisStatus.PLANNING;
        case TOOLS -> AnalysisStatus.TOOL_RUNNING;
        case GENERATE,REPAIR -> AnalysisStatus.GENERATING;
        case COMPLETE -> AnalysisStatus.VALIDATING;
        case DONE -> AnalysisStatus.SUCCEEDED;
    };}
    private static Timestamp at(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
    private static UUID uuid(byte[] value){var buffer=java.nio.ByteBuffer.wrap(value);return new UUID(buffer.getLong(),buffer.getLong());}
    private static CopilotException invalid(){return new CopilotException(ErrorCode.AGENT_CHECKPOINT_INVALID,"Agent checkpoint invalid");}
}
