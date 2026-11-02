package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

public record DiagnosticRulePackRow(
        String projectId,
        String packId,
        String packVersion,
        String name,
        String description,
        String origin,
        String engineMinVersion,
        String jsonBlob,
        Instant createdAt
) {
    public DiagnosticRulePackRow {
        projectId = Objects.requireNonNull(projectId, "projectId");
        packId = Objects.requireNonNull(packId, "packId");
        packVersion = packVersion == null ? "1.0" : packVersion;
        name = name == null ? packId : name;
        description = description == null ? "" : description;
        origin = origin == null ? "PROJECT_CUSTOM" : origin;
        engineMinVersion = engineMinVersion == null ? "1" : engineMinVersion;
        jsonBlob = jsonBlob == null ? "" : jsonBlob;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
