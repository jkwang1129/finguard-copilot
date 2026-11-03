package com.finguard.copilot.observability;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.finguard.copilot.usage.*;
import com.finguard.copilot.model.port.ModelUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryMetricsTest {
    @Test void meterFailureCannotInvalidatePersistedUsage() {
        var meters=new SimpleMeterRegistry();
        meters.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter(){
            @Override public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id){throw new IllegalStateException("SECRET");}
        });
        var recorder=new ModelUsageRecorder(mock(JdbcTemplate.class),mock(TransactionTemplate.class),
                new ModelCostCalculator.PriceTable("v1","CNY",BigDecimal.ONE,BigDecimal.ONE),Clock.systemUTC(),meters);
        assertThatCode(()->recorder.record(UUID.randomUUID(),"deepseek","model-v1","prompt-v1",new ModelUsage(1,1,Duration.ofMillis(1)),"SUCCESS"))
                .doesNotThrowAnyException();
    }
    @Test void changingVersionsAndUntrustedProvidersDoNotCreateNewLabels() {
        var registry=new SimpleMeterRegistry();
        var recorder=new ModelUsageRecorder(mock(JdbcTemplate.class),mock(TransactionTemplate.class),
                new ModelCostCalculator.PriceTable("price-v1","CNY",BigDecimal.ONE,BigDecimal.ONE),Clock.systemUTC(),registry);
        for(int i=0;i<25;i++) recorder.record(UUID.randomUUID(),"secret-user-"+i,"version-"+i,"prompt-v1",new ModelUsage(1,1,Duration.ofMillis(1)),"SUCCESS");
        assertThat(registry.getMeters()).hasSizeLessThanOrEqualTo(5);
        assertThat(registry.getMeters()).allSatisfy(m->{
            assertThat(m.getId().getTags()).allSatisfy(t->assertThat(t.getKey()).isIn("provider","outcome"));
            assertThat(m.getId().getTag("provider")).isEqualTo("OTHER");
        });
        assertThat(registry.find("copilot.model.calls").counter()).isNull();
        assertThat(registry.get("copilot.model.usage.records").counter().count()).isEqualTo(25);
    }
}
