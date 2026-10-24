package com.finguard.copilot.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.finguard.copilot.model.port.ModelUsage;

class ModelCostCalculatorTest {

    private final ModelCostCalculator calculator = new ModelCostCalculator();
    private final ModelCostCalculator.PriceTable prices = new ModelCostCalculator.PriceTable(
            "phase3-price-v1", "USD", new BigDecimal("0.50"), new BigDecimal("1.50"));

    @Test
    void calculatesDecimalCostPerMillionTokensWithStableRounding() {
        ModelCostCalculator.MoneyAmount cost = calculator.estimate(
                new ModelUsage(1_200, 180, Duration.ofMillis(240)), prices);

        assertThat(cost.amount()).isEqualByComparingTo("0.00087000");
        assertThat(cost.currency()).isEqualTo("USD");
    }

    @Test
    void zeroTokenUsageHasZeroCostAndRetainsPriceTableVersion() {
        ModelCostCalculator.MoneyAmount cost = calculator.estimate(
                new ModelUsage(0, 0, Duration.ZERO), prices);

        assertThat(cost.amount()).isEqualByComparingTo("0.00000000");
        assertThat(prices.version()).isEqualTo("phase3-price-v1");
    }
}
