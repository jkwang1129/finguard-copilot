package com.finguard.copilot.agent.runtime;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.context.*;
import com.finguard.copilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
public final class JdbcAgentRunContextLoader implements AgentRunContextLoader {
    private final JdbcTemplate jdbc;private final SnapshotCodec codec;private final Clock clock;
    public JdbcAgentRunContextLoader(JdbcTemplate jdbc,SnapshotCodec codec,Clock clock){this.jdbc=jdbc;this.codec=codec;this.clock=clock;}
    @Override public AgentRunContext load(UUID id){
        var result=jdbc.query("""
            SELECT r.reviewer_username,p.index_version,p.snapshot_sha256,s.facts_json,s.facts_sha256,s.captured_at,s.expires_at
            FROM analysis_run r JOIN agent_run_policy p ON p.analysis_id=r.id
            JOIN analysis_input_snapshot s ON s.analysis_id=r.id
            WHERE r.id=? AND s.deleted_at IS NULL AND s.facts_json IS NOT NULL
            """,(rows,n)->{
                byte[] hash=rows.getBytes("facts_sha256");
                if(!java.security.MessageDigest.isEqual(hash,rows.getBytes("snapshot_sha256"))
                    || !rows.getTimestamp("expires_at").toInstant().isAfter(clock.instant())) throw unavailable();
                var facts=codec.decodeAndVerify(rows.getString("facts_json").getBytes(StandardCharsets.UTF_8),hash);
                return new AgentRunContext(id,rows.getString("reviewer_username"),
                    new FactSnapshot(1,facts,rows.getTimestamp("captured_at").toInstant(),rows.getTimestamp("expires_at").toInstant(),hash),
                    rows.getString("index_version"));
            },JdbcAnalysisRunRepository.uuidBytes(id));
        if(result.size()!=1 || result.get(0).indexVersion()==null) throw unavailable();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_version WHERE index_version=? AND status='PUBLISHED'",
                Integer.class,result.get(0).indexVersion())!=1) throw unavailable();
        return result.get(0);
    }
    private static CopilotException unavailable(){return new CopilotException(ErrorCode.FACT_VALIDATION_FAILED,"Agent captured context unavailable");}
}
