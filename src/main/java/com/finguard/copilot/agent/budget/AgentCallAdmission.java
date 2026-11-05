package com.finguard.copilot.agent.budget;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.finguard.copilot.governance.RedisProviderPermits;

/** Redis permission precedes the durable claim; releasing a lease never releases uncertain money. */
public final class AgentCallAdmission {
    private final AgentCallLedger ledger;
    private final RedisProviderPermits permits;
    private final Duration timeout;
    private final Map<CallClaim,RedisProviderPermits.Lease> active=new ConcurrentHashMap<>();
    public AgentCallAdmission(AgentCallLedger ledger,RedisProviderPermits permits,Duration timeout){
        this.ledger=ledger;this.permits=permits;this.timeout=timeout;
    }
    public CallClaim acquire(UUID analysisId,int ordinal,PreparedAgentCall call){
        return acquire(analysisId,ordinal,call,null);
    }
    public CallClaim acquire(UUID analysisId,int ordinal,PreparedAgentCall call,com.finguard.copilot.agent.runtime.AgentExecutionFence fence){
        var lease=permits.acquire(ledger.reviewerKey(analysisId),timeout);
        CallClaim claim=null;boolean retained=false;
        try{
            claim=fence==null?ledger.acquire(analysisId,ordinal,call):ledger.acquire(analysisId,ordinal,call,fence);
            if(!permits.valid(lease,timeout.multipliedBy(2).plusSeconds(5))){
                ledger.recordOutcome(claim,AgentCallOutcome.notSent());throw RedisProviderPermits.capacity();
            }
            active.put(claim,lease);retained=true;return claim;
        }finally{if(!retained)release(lease);}
    }
    public void close(CallClaim claim){var lease=active.remove(claim);if(lease!=null)release(lease);}
    private void release(RedisProviderPermits.Lease lease){
        try{permits.release(lease);}catch(RuntimeException ignored){/* Owner TTL expires independently of budget. */}
    }
}
