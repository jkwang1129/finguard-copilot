package com.finguard.copilot.agent.graph;
import java.util.*;
import com.finguard.copilot.agent.budget.AgentCallLedger;
import com.finguard.copilot.agent.checkpoint.CheckpointStore;
import com.finguard.copilot.analysis.application.GetAnalysisService;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Authorized projection: no arguments, Prompt, tool body, raw candidate or reasoning. */
@Service
public class AgentTraceService {
    private final GetAnalysisService access;private final CheckpointStore checkpoints;
    private final AgentCallLedger ledger;private final JdbcTemplate jdbc;private final Environment env;
    public AgentTraceService(GetAnalysisService access,CheckpointStore checkpoints,AgentCallLedger ledger,JdbcTemplate jdbc,Environment env){
        this.access=access;this.checkpoints=checkpoints;this.ledger=ledger;this.jdbc=jdbc;this.env=env;
    }
    public record Summary(String workflowVersion,String revision,String node,String waitingReason,String waitExpiresAt,
        String capturedAt,String snapshotExpiresAt,int modelCalls,int toolCalls,List<String> plannedTools,boolean replayable,boolean canResume){}
    public record ToolTrace(int slot,String toolName,String status,String checksum){}
    public record CallTrace(int ordinal,String status,String estimatedCost,String inputTokens,String outputTokens){}
    public record Trace(UUID analysisId,String revision,boolean replayable,List<ToolTrace> tools,List<CallTrace> calls){}
    public Summary summaryAfterAuthorization(UUID id){
        java.util.Optional<com.finguard.copilot.agent.checkpoint.AgentCheckpoint> found;
        try{found=checkpoints.read(id);}catch(com.finguard.copilot.common.error.CopilotException invalid){
            if(invalid.code()!=com.finguard.copilot.common.error.ErrorCode.AGENT_CHECKPOINT_INVALID)throw invalid;
            return invalidSummary(id);
        }
        if(found.isEmpty())return null;
        var cp=found.get();
        var source=jdbc.queryForMap("SELECT captured_at,expires_at,deleted_at,fully_reproducible FROM analysis_input_snapshot s JOIN analysis_run r ON s.analysis_id=r.id WHERE r.id=?",bytes(id));
        boolean replayable=source.get("deleted_at")==null && Boolean.TRUE.equals(source.get("fully_reproducible"))
            && cp.working()!=null && cp.snapshotExpiresAt().isAfter(java.time.Instant.now());
        var captured=jdbc.queryForObject("SELECT captured_at FROM analysis_input_snapshot WHERE analysis_id=?",(rows,n)->rows.getTimestamp(1).toInstant().toString(),bytes(id));
        var totals=ledger.totals(id);
        int tools=jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,bytes(id));
        var planned=cp.working()==null?List.<String>of():cp.working().plannedTools().stream().map(call->call.toolName()).toList();
        return new Summary(cp.workflowVersion(),Long.toString(cp.revision()),cp.node(),cp.waiting()?"REVIEW_BEFORE_TOOLS":null,
            cp.waitExpiresAt()==null?null:cp.waitExpiresAt().toString(),captured,cp.snapshotExpiresAt().toString(),totals.calls(),tools,planned,replayable,
            cp.waiting() && replayable && cp.waitExpiresAt().isAfter(java.time.Instant.now()) && env.getProperty("finguard.copilot.agent.runtime-enabled",Boolean.class,false));
    }
    private Summary invalidSummary(UUID id){
        var rows=jdbc.query("""
            SELECT c.revision,s.captured_at,s.expires_at FROM analysis_checkpoint c
            JOIN analysis_input_snapshot s ON s.analysis_id=c.analysis_id WHERE c.analysis_id=?
            """,(r,n)->new Summary("agent-v1",Long.toString(r.getLong(1)),"INVALID",null,null,
                r.getTimestamp(2).toInstant().toString(),r.getTimestamp(3).toInstant().toString(),ledger.totals(id).calls(),
                jdbc.queryForObject("SELECT COUNT(*) FROM analysis_tool_execution WHERE analysis_id=?",Integer.class,bytes(id)),List.of(),false,false),bytes(id));
        return rows.isEmpty()?null:rows.get(0);
    }
    public Trace view(UUID id,String token){
        access.authorize(id,token);
        var summary=summaryAfterAuthorization(id);
        var tools=jdbc.query("SELECT slot,tool_name,status,result_sha256 FROM analysis_tool_execution WHERE analysis_id=? ORDER BY slot",
            (row,n)->new ToolTrace(row.getInt(1),row.getString(2),row.getString(3),row.getString(4)),bytes(id));
        var calls=jdbc.query("SELECT call_ordinal,status,settled_cny,input_tokens,output_tokens FROM agent_model_call WHERE analysis_id=? ORDER BY call_ordinal",
            (row,n)->new CallTrace(row.getInt(1),row.getString(2),row.getBigDecimal(3)==null?null:row.getBigDecimal(3).toPlainString(),row.getString(4),row.getString(5)),bytes(id));
        return new Trace(id,summary==null?"0":summary.revision(),summary!=null && summary.replayable(),tools,calls);
    }
    private static byte[] bytes(UUID id){return JdbcAnalysisRunRepository.uuidBytes(id);}
}
