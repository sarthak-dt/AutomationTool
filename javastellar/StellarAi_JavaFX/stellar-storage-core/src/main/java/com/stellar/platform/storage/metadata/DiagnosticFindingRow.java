package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DiagnosticFindingRow(
        String findingId,
        String runId,
        String projectId,
        String ruleId,
        String ruleVersion,
        String severity,
        String category,
        long startTimestampNanos,
        Long endTimestampNanos,
        String summary,
        String details,
        String evidenceBlob,
        Instant createdAt
) {
    public DiagnosticFindingRow {
        findingId = findingId == null || findingId.isBlank() ? UUID.randomUUID().toString() : findingId;
        runId = Objects.requireNonNull(runId, "runId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        ruleId = ruleId == null ? "" : ruleId;
        ruleVersion = ruleVersion == null ? "" : ruleVersion;
        severity = severity == null ? "OBSERVATION" : severity;
        category = category == null ? "CUSTOM" : category;
        summary = summary == null ? "" : summary;
        details = details == null ? "" : details;
        evidenceBlob = evidenceBlob == null ? "" : evidenceBlob;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
