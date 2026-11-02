package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticBindingRow(
        String projectId,
        String packId,
        String role,
        String signalKeyBlob,
        Instant updatedAt
) {
    public DiagnosticBindingRow {
        projectId = Objects.requireNonNull(projectId, "projectId");
        packId = packId == null ? "" : packId;
        role = Objects.requireNonNull(role, "role");
        signalKeyBlob = signalKeyBlob == null ? "" : signalKeyBlob;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
