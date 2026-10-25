package com.finguard.copilot.usage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.finguard.copilot.model.port.ModelUsage;

class ModelUsageRecorderTest {

    @Test
    void rejectsUnknownOutcomeBeforeWritingUsage() {
        ModelUsageRecorder recorder = new ModelUsageRecorder(
                new JdbcTemplate(), new TransactionTemplate(),
                new ModelCostCalculator.PriceTable("price-v1", "USD", BigDecimal.ONE, BigDecimal.ONE),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null);

        assertThatThrownBy(() -> recorder.record(UUID.randomUUID(), "qwen", "model-v1", "prompt-v1",
                new ModelUsage(1, 1, java.time.Duration.ZERO), "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
