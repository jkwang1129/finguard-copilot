package com.finguard.copilot.governance;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Acquire a short-lived Redis permit before the atomic money/call claim. */
public final class CallAdmission {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CallAdmission.class);
    private final Supplier<BudgetReservations> budgets;
    private final RedisProviderPermits permits;
    private final Duration timeout;
    private final ConcurrentMap<UUID,RedisProviderPermits.Lease> active=new ConcurrentHashMap<>();
    public CallAdmission(Supplier<BudgetReservations> budgets,RedisProviderPermits permits,Duration timeout) {
        this.budgets=budgets;this.permits=permits;this.timeout=timeout;
    }
    public void open(UUID id) {
        var budget=budgets.get();
        var frozen=budget.frozen(id);
        var permit=permits.acquire(frozen.manifest().path("reviewerKey").asText(),timeout);
        boolean retained=false,claimed=false;
        try {
            claimed=budget.acquire(id);
            if(!claimed) throw new ExistingModelCallException();
            // Both connect and response deadlines plus cleanup must fit the lease.
            if(!permits.valid(permit,timeout.multipliedBy(2).plusSeconds(5))) {
                throw RedisProviderPermits.capacity();
            }
            if(active.putIfAbsent(id,permit)!=null) {
                throw RunManifestService.rejected("active admission ownership conflict");
            }
            retained=true;
        } catch(com.finguard.copilot.common.error.CopilotException failure) {
            // This branch has not invoked HTTP; only a positively acknowledged new
            // claim can be released. An uncertain commit must remain held.
            if(claimed) {
                budget.releaseNotSent(id);
                throw new ClaimedCallNotSentException(failure.code());
            }
            throw failure;
        } finally {
            if(!retained) release(permit);
        }
    }
    public void close(UUID id) {
        var permit=active.remove(id);
        if(permit!=null) release(permit);
    }
    private void release(RedisProviderPermits.Lease permit) {
        try {permits.release(permit);}
        catch(RuntimeException unavailable) {LOG.warn("model permit release deferred to lease expiry");}
    }
}
