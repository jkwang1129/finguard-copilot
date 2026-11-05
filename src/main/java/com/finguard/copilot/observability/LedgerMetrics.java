package com.finguard.copilot.observability;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Read-only, process-independent ledger aggregates; stale snapshots are explicitly observable. */
@Component
public final class LedgerMetrics {
    private static final List<String> STATES=List.of("RESERVED","SETTLED","UNKNOWN_HELD","RELEASED_NOT_SENT");
    private record Amounts(double count,double held,double settled) {}
    private record Snapshot(Map<String,Amounts> states,double calls,double usage,double responses,long at) {}
    private final AtomicReference<Snapshot> snapshot=new AtomicReference<>();
    private final JdbcTemplate jdbc; private final Clock clock;
    private volatile double healthy=0;
    private final Counter failures;
    private final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"copilot-ledger-metrics");thread.setDaemon(true);return thread;
    });
    @Autowired public LedgerMetrics(JdbcTemplate original,MeterRegistry meters) {
        this(new JdbcTemplate(Objects.requireNonNull(original.getDataSource())),meters,Clock.systemUTC());
        jdbc.setQueryTimeout(2);
    }
    LedgerMetrics(JdbcTemplate jdbc,MeterRegistry meters,Clock clock) {
        this.jdbc=jdbc;this.clock=clock;
        for(String state:STATES) {
            Gauge.builder("copilot.ledger.reservations",()->amount(state,0)).tag("state",state).register(meters);
            Gauge.builder("copilot.ledger.held.cny",()->amount(state,1)).tag("state",state).register(meters);
        }
        Gauge.builder("copilot.ledger.settled.estimate.cny",()->amount("SETTLED",2)).register(meters);
        Gauge.builder("copilot.ledger.call.slots",()->value(0)).register(meters);
        Gauge.builder("copilot.ledger.usage.records",()->value(1)).register(meters);
        Gauge.builder("copilot.ledger.provider.responses",()->value(2)).register(meters);
        Gauge.builder("copilot.ledger.refresh.success",()->healthy).register(meters);
        Gauge.builder("copilot.ledger.snapshot.age.seconds",()->snapshot.get()==null?Double.NaN:Math.max(0,(clock.millis()-snapshot.get().at())/1000d)).register(meters);
        failures=Counter.builder("copilot.ledger.refresh.failures").register(meters);
    }
    private double amount(String state,int field) {
        var s=snapshot.get();if(s==null) return Double.NaN;var v=s.states().get(state);
        return field==0?v.count():field==1?v.held():v.settled();
    }
    private double value(int field) {var s=snapshot.get();return s==null?Double.NaN:field==0?s.calls():field==1?s.usage():s.responses();}
    @Scheduled(fixedDelayString="${finguard.copilot.telemetry.ledger-refresh:30s}",initialDelayString="${finguard.copilot.telemetry.ledger-initial-delay:5s}")
    public void poll() {
        if(!polling.compareAndSet(false,true)) return;
        try {executor.execute(()->{try {refresh();} finally {polling.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException ignored) {polling.set(false);}
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
    public void refresh() {
        try {
            var states=new HashMap<String,Amounts>();for(String state:STATES) states.put(state,new Amounts(0,0,0));
            for(var row:jdbc.queryForList("""
                    SELECT status,COUNT(*) AS count,
                      COALESCE(SUM(CASE WHEN status IN ('RESERVED','UNKNOWN_HELD') THEN reserved_cny ELSE 0 END),0) AS held,
                      COALESCE(SUM(CASE WHEN status='SETTLED' THEN settled_cny ELSE 0 END),0) AS settled
                    FROM (SELECT status,reserved_cny,settled_cny FROM model_budget_reservation
                      UNION ALL SELECT status,reserved_cny,settled_cny FROM agent_model_call) calls GROUP BY status
                    """)) {
                String state=(String)row.get("status");if(!STATES.contains(state)) throw new IllegalStateException();
                states.put(state,new Amounts(number(row.get("count")),number(row.get("held")),number(row.get("settled"))));
            }
            long calls=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM model_call_reservation)+(SELECT COUNT(*) FROM agent_model_call)",Long.class);
            long usage=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM model_usage_ledger)+(SELECT COUNT(*) FROM agent_model_call WHERE input_tokens IS NOT NULL)",Long.class);
            long responses=jdbc.queryForObject("SELECT (SELECT COUNT(DISTINCT analysis_id) FROM model_budget_event WHERE event_type='PROVIDER_RESPONSE')+(SELECT COUNT(*) FROM agent_model_call WHERE provider_response_sha256 IS NOT NULL)",Long.class);
            snapshot.set(new Snapshot(Map.copyOf(states),calls,usage,responses,clock.millis()));healthy=1;
        } catch(RuntimeException ignored){healthy=0;failures.increment();}
    }
    private double number(Object value) {
        if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0) throw new IllegalArgumentException();
        return n.doubleValue();
    }
}
