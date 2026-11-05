package com.finguard.copilot.agent.runtime;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;

/** Server-injected execution authority. Check only while holding the analysis_run row lock. */
public record AgentExecutionFence(String owner,long revision) {
    public AgentExecutionFence {
        if(owner==null || owner.isBlank() || revision<0)throw new IllegalArgumentException("Invalid execution fence");
    }
    public static void requireCurrent(JdbcTemplate jdbc,UUID id,AgentExecutionFence fence,Clock clock){
        byte[] key=JdbcAnalysisRunRepository.uuidBytes(id);
        var rows=jdbc.query("SELECT owner_token,revision,waiting,lease_until FROM analysis_checkpoint WHERE analysis_id=?",
            (r,n)->new Ownership(r.getString(1),r.getLong(2),r.getBoolean(3),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant()),key);
        if(fence==null){
            // Standalone primitive tests/capture-only runs predate the durable runtime. Graph runs never use this path.
            if(!rows.isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_policy WHERE analysis_id=? AND runtime_enabled=TRUE",Integer.class,key)>0)throw new Lost();
            return;
        }
        if(rows.size()!=1)throw new Lost();
        var row=rows.get(0);
        if(!Objects.equals(fence.owner(),row.owner()) || fence.revision()!=row.revision() || row.waiting()
            || row.lease()==null || !row.lease().isAfter(clock.instant()))throw new Lost();
        String status=jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?",String.class,key);
        if(java.util.Set.of("SUCCEEDED","FAILED","WAITING_FOR_HUMAN").contains(status))throw new Lost();
    }
    private record Ownership(String owner,long revision,boolean waiting,java.time.Instant lease){}
    public static final class Lost extends CopilotException {
        public Lost(){super(ErrorCode.CONFLICT,"Agent execution lease lost");}
    }
}
