package com.finguard.copilot.agent.hitl;
import com.finguard.copilot.agent.checkpoint.CheckpointStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="finguard.copilot.agent",name="runtime-enabled",havingValue="true")
public final class WaitingAnalysisExpiryJob {
    private final CheckpointStore checkpoints;
    public WaitingAnalysisExpiryJob(CheckpointStore checkpoints){this.checkpoints=checkpoints;}
    @Scheduled(fixedDelayString="${finguard.copilot.agent.recovery-poll:10s}")
    public void recover(){
        checkpoints.expireWaiting(100);
        checkpoints.requeueExpired(100);
    }
}
