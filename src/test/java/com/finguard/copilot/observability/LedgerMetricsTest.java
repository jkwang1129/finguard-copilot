package com.finguard.copilot.observability;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LedgerMetricsTest {
    @Test void aggregateQueriesIncludeBothAgentAndLegacyLedgers(){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(0L);
        var metrics=new LedgerMetrics(jdbc,new SimpleMeterRegistry(),Clock.systemUTC());metrics.refresh();metrics.close();
        verify(jdbc).queryForList(contains("agent_model_call"));
        verify(jdbc,atLeastOnce()).queryForObject(contains("agent_model_call"),eq(Long.class));
    }
    @Test void slowDatabaseDoesNotBlockTheSchedulerOrCreateUnboundedRefreshTasks() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenAnswer(i->{entered.countDown();release.await();return List.of();});
        var metrics=new LedgerMetrics(jdbc,new SimpleMeterRegistry(),Clock.systemUTC());
        try {
            metrics.poll();assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long begin=System.nanoTime();for(int i=0;i<100;i++) metrics.poll();
            assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(1));
            verify(jdbc,times(1)).queryForList(anyString());
        } finally {release.countDown();metrics.close();}
    }
    @Test void unknownHeldAndUnsentHaveSeparateMeaningAndFailurePreservesSnapshot() {
        var jdbc=mock(JdbcTemplate.class);var meters=new SimpleMeterRegistry();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("status","UNKNOWN_HELD","count",2L,"held",new BigDecimal("0.15"),"settled",BigDecimal.ZERO),
                Map.of("status","RELEASED_NOT_SENT","count",1L,"held",BigDecimal.ZERO,"settled",BigDecimal.ZERO),
                Map.of("status","SETTLED","count",3L,"held",BigDecimal.ZERO,"settled",new BigDecimal("0.02"))));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(6L);
        var metrics=new LedgerMetrics(jdbc,meters,Clock.systemUTC());metrics.refresh();
        assertThat(meters.get("copilot.ledger.reservations").tag("state","UNKNOWN_HELD").gauge().value()).isEqualTo(2);
        assertThat(meters.get("copilot.ledger.held.cny").tag("state","UNKNOWN_HELD").gauge().value()).isEqualTo(0.15);
        assertThat(meters.get("copilot.ledger.reservations").tag("state","RELEASED_NOT_SENT").gauge().value()).isEqualTo(1);
        assertThat(meters.get("copilot.ledger.settled.estimate.cny").gauge().value()).isEqualTo(0.02);
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("SECRET"));metrics.refresh();
        assertThat(meters.get("copilot.ledger.refresh.success").gauge().value()).isZero();
        assertThat(meters.get("copilot.ledger.held.cny").tag("state","UNKNOWN_HELD").gauge().value()).isEqualTo(0.15);
        assertThat(meters.getMeters()).allSatisfy(m->assertThat(m.getId().getTags()).allSatisfy(t->assertThat(t.getKey()).isEqualTo("state")));
    }
}
