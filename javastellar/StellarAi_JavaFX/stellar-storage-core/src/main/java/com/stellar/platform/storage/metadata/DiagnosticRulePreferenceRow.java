package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticRulePreferenceRow(
        String projectId,
        String packId,
        String ruleId,
        boolean enabled,
        Instant updatedAt
) {
    public DiagnosticRulePreferenceRow {
        projectId = Objects.requireNonNull(projectId, "projectId");
        packId = packId == null ? "" : packId;
        ruleId = Objects.requireNonNull(ruleId, "ruleId");
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
