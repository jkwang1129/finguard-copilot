package com.finguard.copilot.coreclient.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finguard.copilot.coreclient.port.ReviewTaskState;

/** Intentionally project only these five fields from Core's broader task DTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CoreReviewTaskStateResponse(Long id, Long version, String status, String ruleCode, String reasonCode) {
    ReviewTaskState toPort() {
        if (id == null || version == null) throw new IllegalArgumentException("task identifiers are required");
        return new ReviewTaskState(id, version, status, ruleCode, reasonCode);
    }
}
