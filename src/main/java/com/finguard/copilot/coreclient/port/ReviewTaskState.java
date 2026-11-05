package com.finguard.copilot.coreclient.port;

import java.util.Objects;

/** Minimal task projection. No reviewer identity or free-form decision text. */
public record ReviewTaskState(long id, long version, String status, String ruleCode, String reasonCode) {
    public ReviewTaskState {
        if (id < 1 || version < 0 || status == null || status.isBlank()
                || reasonCode == null || reasonCode.isBlank() || ruleCode != null && ruleCode.isBlank()) {
            throw new IllegalArgumentException("task state is invalid");
        }
    }

    public boolean matches(ReviewTaskContext.ReviewTask task) {
        return id == task.id() && version == task.version() && status.equals(task.status())
                && Objects.equals(ruleCode, task.ruleCode()) && reasonCode.equals(task.reasonCode());
    }
}
