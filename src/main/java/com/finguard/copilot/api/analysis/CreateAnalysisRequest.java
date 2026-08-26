package com.finguard.copilot.api.analysis;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAnalysisRequest(
        @NotNull @Positive Long reviewTaskId
) {
}
