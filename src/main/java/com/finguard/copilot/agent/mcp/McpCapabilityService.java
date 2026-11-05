package com.finguard.copilot.agent.mcp;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.agent.checkpoint.*;
import com.finguard.copilot.agent.runtime.*;
import com.finguard.copilot.agent.tool.ToolRegistry;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.*;
import com.finguard.copilot.governance.CanonicalJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public class McpCapabilityService {
    private final GetAnalysisService access;private final CoreClient core;private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;private final CheckpointStore checkpoints;private final SnapshotCodec codec;private final Clock clock;
    @Autowired public McpCapabilityService(GetAnalysisService access,CoreClient core,JdbcTemplate jdbc,TransactionTemplate tx,CheckpointStore checkpoints,SnapshotCodec codec){
        this(access,core,jdbc,tx,checkpoints,codec,Clock.systemUTC());
    }
    public McpCapabilityService(GetAnalysisService access,CoreClient core,JdbcTemplate jdbc,TransactionTemplate tx,CheckpointStore checkpoints,SnapshotCodec codec,Clock clock){
        this.access=access;this.core=core;this.jdbc=jdbc;this.tx=tx;this.checkpoints=checkpoints;this.codec=codec;this.clock=clock;
    }
    public record IssuedCapability(String capability,String revision,List<String> toolScope,Instant expiresAt){
        @Override public String toString(){return "IssuedCapability[redacted]";}
    }
    public IssuedCapability issue(UUID id,List<String> scope,String token){
        if(scope==null || scope.isEmpty() || scope.size()>3 || new HashSet<>(scope).size()!=scope.size()
            || !new ToolRegistry().names().containsAll(scope))throw new CopilotException(ErrorCode.INVALID_REQUEST,"Invalid MCP scope");
        String reviewer=access.authorize(id,token).reviewer();
        var context=new JdbcAgentRunContextLoader(jdbc,codec,clock).load(id);
        long task=context.snapshot().facts().reviewTask().id();
        try{
            var fresh=core.reviewTaskContext(task,token);var state=core.reviewTaskState(task,token);
            if(fresh==null || state==null || !state.matches(fresh.reviewTask()) || !Set.of("PENDING","OPEN").contains(state.status())
                || !java.security.MessageDigest.isEqual(semantic(context.snapshot().facts()),semantic(fresh)))
                throw new CopilotException(ErrorCode.CONTEXT_STALE,"MCP source changed; create a new analysis");
        }catch(CoreClientException e){throw new CopilotException(switch(e.kind()){
            case UNAUTHENTICATED->ErrorCode.UNAUTHENTICATED;case FORBIDDEN->ErrorCode.FORBIDDEN;
            case NOT_FOUND->ErrorCode.RESOURCE_NOT_FOUND;default->ErrorCode.SERVICE_UNAVAILABLE;
        },"Core authorization or context unavailable");}
        return tx.execute(transaction->{
            String status=checkpoints.lock(id);var cp=checkpoints.read(id).orElseThrow(McpCapabilityService::conflict);
            requireIdle(status,cp);requireSource(id);
            byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
            String capability=Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
            Instant expiry=Collections.min(List.of(clock.instant().plusSeconds(300),cp.snapshotExpiresAt()));
            jdbc.update("INSERT INTO analysis_mcp_capability(capability_sha256,analysis_id,reviewer_username,revision,tool_scope_json,expires_at) VALUES(?,?,?,?,?,?)",
                CanonicalJson.sha256(capability),bytes(id),reviewer,cp.revision(),CanonicalJson.encode(scope),java.sql.Timestamp.from(expiry));
            return new IssuedCapability(capability,Long.toString(cp.revision()),List.copyOf(scope),expiry);
        });
    }
    public AuthorizedMcpSession authorize(UUID id,String capability){
        if(capability==null || !capability.matches("[A-Za-z0-9_-]{43}"))throw denied();
        return validateHash(id,CanonicalJson.sha256(capability));
    }
    public AuthorizedMcpSession validate(AuthorizedMcpSession session){return validateHash(session.analysisId(),session.capabilityHash());}
    public record ToolScope(String snapshotHash,String indexVersion){}
    ToolScope scope(AuthorizedMcpSession session){
        var context=new JdbcAgentRunContextLoader(jdbc,codec,clock).load(session.analysisId());
        return new ToolScope(HexFormat.of().formatHex(context.snapshot().sha256()),context.indexVersion());
    }
    private AuthorizedMcpSession validateHash(UUID id,String hash){
        return tx.execute(transaction->{
            String status=checkpoints.lock(id);
            var rows=jdbc.queryForList("SELECT reviewer_username,revision,tool_scope_json FROM analysis_mcp_capability WHERE capability_sha256=? AND analysis_id=? AND revoked_at IS NULL AND expires_at>?",hash,bytes(id),java.sql.Timestamp.from(clock.instant()));
            if(rows.size()!=1)throw denied();
            var row=rows.get(0);var cp=checkpoints.read(id).orElseThrow(McpCapabilityService::conflict);requireIdle(status,cp);requireSource(id);
            if(cp.revision()!=((Number)row.get("revision")).longValue())throw conflict();
            String reviewer=(String)row.get("reviewer_username");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run WHERE id=? AND BINARY reviewer_username=BINARY ?",Integer.class,bytes(id),reviewer)!=1)throw denied();
            Set<String> tools=new HashSet<>();
            try{for(var item:new ObjectMapper().readTree((String)row.get("tool_scope_json")))tools.add(item.asText());}
            catch(java.io.IOException e){throw denied();}
            if(tools.isEmpty() || !new ToolRegistry().names().containsAll(tools))throw denied();
            return new AuthorizedMcpSession(id,reviewer,hash,cp.revision(),tools);
        });
    }
    public void revoke(UUID id,String token){
        String reviewer=access.authorize(id,token).reviewer();
        tx.executeWithoutResult(transaction->{checkpoints.lock(id);jdbc.update("UPDATE analysis_mcp_capability SET revoked_at=? WHERE analysis_id=? AND BINARY reviewer_username=BINARY ? AND revoked_at IS NULL",java.sql.Timestamp.from(clock.instant()),bytes(id),reviewer);});
    }
    private void requireIdle(String status,AgentCheckpoint cp){
        if(!Set.of("WAITING_FOR_HUMAN","SUCCEEDED","FAILED").contains(status)
            || "WAITING_FOR_HUMAN".equals(status) && (!cp.waiting() || cp.waitExpiresAt()==null || !cp.waitExpiresAt().isAfter(clock.instant())))throw conflict();
    }
    private void requireSource(UUID id){new JdbcAgentRunContextLoader(jdbc,codec,clock).load(id);}
    private byte[] semantic(ReviewTaskContext facts){return codec.capture(new ReviewTaskContext(facts.reviewTask(),facts.transaction(),facts.reconciliation(),facts.riskFacts(),facts.accountSummary(),Instant.EPOCH)).sha256();}
    private static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
    private static CopilotException denied(){return new CopilotException(ErrorCode.UNAUTHENTICATED,"MCP capability is unavailable");}
    private static CopilotException conflict(){return new CopilotException(ErrorCode.CONFLICT,"MCP requires the frozen waiting or terminal revision");}
}
