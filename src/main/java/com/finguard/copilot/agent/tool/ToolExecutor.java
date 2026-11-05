package com.finguard.copilot.agent.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.*;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.governance.CanonicalJson;
import com.finguard.copilot.knowledge.retrieval.KnowledgeRetrievalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims are committed before execution. An uncertain claim is never deleted or executed again. */
public final class ToolExecutor {
    private com.finguard.copilot.observability.AnalysisTelemetry telemetry=com.finguard.copilot.observability.AnalysisTelemetry.noop();
    public ToolExecutor telemetry(com.finguard.copilot.observability.AnalysisTelemetry value){telemetry=Objects.requireNonNull(value);return this;}
    private com.finguard.copilot.agent.graph.AgentFaultHooks faults=com.finguard.copilot.agent.graph.AgentFaultHooks.none();
    public ToolExecutor faults(com.finguard.copilot.agent.graph.AgentFaultHooks value){faults=Objects.requireNonNull(value);return this;}
    private void fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point point,ToolExecutionContext context){faults.hit(point,context.analysisId());}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SnapshotCodec codec;
    private final KnowledgeRetrievalService retrieval;
    private final ToolRegistry registry;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public ToolExecutor(JdbcTemplate jdbc,TransactionTemplate transactions,SnapshotCodec codec,
                        KnowledgeRetrievalService retrieval,ToolRegistry registry,Clock clock) {
        this.jdbc=jdbc;this.codec=codec;this.retrieval=retrieval;this.registry=registry;this.clock=clock;
        this.tx=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public ToolResult execute(ToolExecutionContext context,ToolInvocation invocation) {
        return telemetry.call(com.finguard.copilot.observability.AnalysisTelemetry.Stage.AGENT_TOOL,()->{
            telemetry.analysisId(context.analysisId());telemetry.toolName(invocation.toolName());return executeOnce(context,invocation);
        });
    }
    private ToolResult executeOnce(ToolExecutionContext context,ToolInvocation invocation) {
        String hash=CanonicalJson.hash(Map.of("toolName",invocation.toolName(),"arguments",invocation.arguments()));
        fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.TOOL_BEFORE_CLAIM,context);
        Claim claim=Objects.requireNonNull(tx.execute(status->claim(context,invocation,hash)));
        if(claim.replay()!=null) return claim.replay();
        fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.TOOL_AFTER_CLAIM,context);
        try {
            registry.validate(invocation.toolName(),invocation.arguments());
            JsonNode data;
            String source;
            fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.TOOL_BEFORE_READ,context);
            switch(invocation.toolName()) {
                case "get_review_facts" -> {data=CanonicalJson.tree(claim.facts());source="CORE_SNAPSHOT";}
                case "get_task_state" -> {data=CanonicalJson.tree(claim.facts().reviewTask());source="CORE_SNAPSHOT";}
                case "search_review_policy" -> {
                    if(context.indexVersion()==null) throw new CopilotException(ErrorCode.KNOWLEDGE_UNAVAILABLE,"Tool policy index unavailable");
                    var args=invocation.arguments();
                    var batch=retrieval.retrievePinned(context.indexVersion(),args.path("query").asText(),args.path("topK").asInt());
                    if(!context.indexVersion().equals(batch.indexVersion())) throw ToolRegistry.rejected();
                    data=CanonicalJson.tree(batch);source="FROZEN_POLICY_INDEX";
                }
                default -> throw ToolRegistry.rejected();
            }
            String content=CanonicalJson.encode(data);
            fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.TOOL_AFTER_READ,context);
            if(content.getBytes(StandardCharsets.UTF_8).length>65536) throw ToolRegistry.rejected();
            ToolRegistry.checkUntrusted(content);
            var result=new ToolResult(invocation.toolName(),source,data,CanonicalJson.hash(data));
            String stored=CanonicalJson.encode(result);
            int updated=Objects.requireNonNull(tx.execute(status->{
                jdbc.queryForObject("SELECT id FROM analysis_run WHERE id=? FOR UPDATE",byte[].class,bytes(context));
                int live=jdbc.queryForObject("SELECT COUNT(*) FROM analysis_input_snapshot WHERE analysis_id=? AND facts_json IS NOT NULL AND deleted_at IS NULL AND expires_at>?",Integer.class,bytes(context),java.sql.Timestamp.from(clock.instant()));
                if(live!=1)throw new CopilotException(ErrorCode.CONTEXT_STALE,"Tool source expired before persistence");
                return jdbc.update("""
                UPDATE analysis_tool_execution SET status='SUCCEEDED',result_json=?,result_sha256=?,
                  completed_at=CURRENT_TIMESTAMP(6) WHERE analysis_id=? AND step_key=? AND status='CLAIMED'
                """,stored,CanonicalJson.sha256(stored),bytes(context),context.stepKey());
            }));
            if(updated!=1) throw new CopilotException(ErrorCode.CONFLICT,"Tool execution outcome unknown");
            fault(com.finguard.copilot.agent.graph.AgentFaultHooks.Point.TOOL_AFTER_PERSIST,context);
            return result;
        } catch(RuntimeException exception) {
            // If this write fails, the committed CLAIMED row still prevents another execution.
            String state=exception instanceof CopilotException ? "REJECTED" : "UNKNOWN";
            tx.executeWithoutResult(status->jdbc.update("""
                UPDATE analysis_tool_execution SET status=?,failure_code=?,completed_at=CURRENT_TIMESTAMP(6)
                WHERE analysis_id=? AND step_key=? AND status='CLAIMED'
                """,state,state.equals("REJECTED")?"TOOL_REJECTED":"TOOL_OUTCOME_UNKNOWN",bytes(context),context.stepKey()));
            if(exception instanceof CopilotException controlled) throw controlled;
            throw new CopilotException(ErrorCode.SERVICE_UNAVAILABLE,"Tool execution outcome unknown");
        }
    }
    private Claim claim(ToolExecutionContext context,ToolInvocation invocation,String hash) {
        var owners=jdbc.queryForList("SELECT reviewer_username FROM analysis_run WHERE id=? FOR UPDATE",bytes(context));
        if(owners.size()!=1 || !context.reviewer().equals(owners.get(0).get("reviewer_username"))) {
            throw new CopilotException(ErrorCode.FORBIDDEN,"Tool scope rejected");
        }
        if(context.mcpRevision()!=null){
            int granted=jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_mcp_capability c JOIN analysis_checkpoint p ON p.analysis_id=c.analysis_id
                JOIN analysis_run r ON r.id=c.analysis_id WHERE c.capability_sha256=? AND c.analysis_id=?
                AND BINARY c.reviewer_username=BINARY ? AND c.revision=? AND p.revision=c.revision
                AND c.expires_at>? AND c.revoked_at IS NULL AND JSON_CONTAINS(c.tool_scope_json,JSON_QUOTE(?))
                AND (r.status IN ('SUCCEEDED','FAILED') OR (r.status='WAITING_FOR_HUMAN' AND p.waiting=TRUE AND p.wait_expires_at>?))
                """,Integer.class,context.mcpCapabilityHash(),bytes(context),context.reviewer(),context.mcpRevision(),
                java.sql.Timestamp.from(clock.instant()),invocation.toolName(),java.sql.Timestamp.from(clock.instant()));
            if(granted!=1)throw new CopilotException(ErrorCode.CONFLICT,"MCP capability or revision changed before tool claim");
        }else com.finguard.copilot.agent.runtime.AgentExecutionFence.requireCurrent(jdbc,context.analysisId(),context.executionFence(),clock);
        var scopes=jdbc.query("""
            SELECT p.snapshot_sha256 AS policy_sha,p.index_version,s.facts_json,s.facts_sha256,s.expires_at,s.deleted_at
            FROM agent_run_policy p JOIN analysis_input_snapshot s ON p.analysis_id=s.analysis_id
            WHERE p.analysis_id=?
            """,(rows,n)->{
                var values=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rows,n);
                values.put("expires_at",rows.getTimestamp("expires_at"));
                return values;
            },bytes(context));
        if(scopes.size()!=1) throw new CopilotException(ErrorCode.FORBIDDEN,"Tool workflow rejected");
        var scope=scopes.get(0);
        byte[] sha=(byte[])scope.get("facts_sha256");
        if(scope.get("deleted_at")!=null || !((java.sql.Timestamp)scope.get("expires_at")).toInstant().isAfter(clock.instant())
            || !MessageDigest.isEqual(sha,(byte[])scope.get("policy_sha"))
            || !HexFormat.of().formatHex(sha).equals(context.snapshotHash())
            || !Objects.equals(scope.get("index_version"),context.indexVersion())) {
            throw new CopilotException(ErrorCode.CONFLICT,"Tool snapshot or index expired or changed");
        }
        ReviewTaskContext facts;
        try {facts=codec.decodeAndVerify(((String)scope.get("facts_json")).getBytes(StandardCharsets.UTF_8),sha);}
        catch(IllegalArgumentException exception){throw ToolRegistry.rejected();}
        if ("search_review_policy".equals(invocation.toolName()) && (context.indexVersion()==null
                || jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_version WHERE index_version=? AND status='PUBLISHED'",
                    Integer.class,context.indexVersion())!=1)) {
            throw new CopilotException(ErrorCode.KNOWLEDGE_UNAVAILABLE,"Tool frozen index is not published");
        }
        var prior=jdbc.queryForList("SELECT * FROM analysis_tool_execution WHERE analysis_id=? AND step_key=?",
            bytes(context),context.stepKey());
        if(!prior.isEmpty()) {
            var row=prior.get(0);
            if(!hash.equals(row.get("arguments_sha256"))) throw new CopilotException(ErrorCode.CONFLICT,"Tool step conflict");
            if(!"SUCCEEDED".equals(row.get("status"))) {
                throw new CopilotException(ErrorCode.CONFLICT,"Tool outcome unknown or rejected; execution will not repeat");
            }
            String stored=(String)row.get("result_json");
            if(stored==null || !CanonicalJson.sha256(stored).equals(row.get("result_sha256"))) throw ToolRegistry.rejected();
            try {
                var result=json.readValue(stored,ToolResult.class);
                if(!result.checksum().equals(CanonicalJson.hash(result.data()))) throw ToolRegistry.rejected();
                return new Claim(facts,result);
            } catch(java.io.IOException exception){throw ToolRegistry.rejected();}
        }
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,bytes(context));
        if(count>=4) throw new CopilotException(ErrorCode.MODEL_BUDGET_EXCEEDED,"Tool call budget exhausted");
        String safeName=registry.names().contains(invocation.toolName())?invocation.toolName():"OTHER";
        jdbc.update("""
            INSERT INTO analysis_tool_execution(analysis_id,slot,step_key,tool_name,arguments_sha256,status)
            VALUES(?,?,?,?,?,'CLAIMED')
            """,bytes(context),count+1,context.stepKey(),safeName,hash);
        return new Claim(facts,null);
    }
    private static byte[] bytes(ToolExecutionContext context){return JdbcAnalysisRunRepository.uuidBytes(context.analysisId());}
    private record Claim(ReviewTaskContext facts,ToolResult replay){}
}
