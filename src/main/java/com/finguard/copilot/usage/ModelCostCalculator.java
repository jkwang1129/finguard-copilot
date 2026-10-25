package com.finguard.copilot.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.finguard.copilot.model.port.ModelUsage;

public final class ModelCostCalculator {

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int MONEY_SCALE = 8;

    public MoneyAmount estimate(ModelUsage usage, PriceTable priceTable) {
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(priceTable, "priceTable must not be null");
        BigDecimal inputCost = priceTable.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(usage.inputTokens()))
                .divide(TOKENS_PER_MILLION, MONEY_SCALE + 4, RoundingMode.HALF_UP);
        BigDecimal outputCost = priceTable.outputPricePerMillion()
                .multiply(BigDecimal.valueOf(usage.outputTokens()))
                .divide(TOKENS_PER_MILLION, MONEY_SCALE + 4, RoundingMode.HALF_UP);
        return new MoneyAmount(inputCost.add(outputCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                priceTable.currency());
    }

    public record PriceTable(
            String version,
            String currency,
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion
    ) {
        public PriceTable {
            requireText(version, "version");
            requireText(currency, "currency");
            requireNonNegative(inputPricePerMillion, "inputPricePerMillion");
            requireNonNegative(outputPricePerMillion, "outputPricePerMillion");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        private static void requireNonNegative(BigDecimal value, String name) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }
    }

    public record MoneyAmount(BigDecimal amount, String currency) {
        public MoneyAmount {
            if (amount == null || amount.signum() < 0 || amount.scale() > MONEY_SCALE) {
                throw new IllegalArgumentException("money amount is invalid");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency must not be blank");
            }
        }
    }
}
