package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lightweight derived-signal identity. Waveform samples are never stored here. */
public record DerivedSignalDefinitionMetadata(
        String derivedId,
        String projectId,
        String identity,
        String expression,
        String alignmentPolicy,
        String analysisVersion,
        Instant createdAt
) {
    public DerivedSignalDefinitionMetadata {
        derivedId = derivedId == null || derivedId.isBlank() ? UUID.randomUUID().toString() : derivedId;
        projectId = Objects.requireNonNull(projectId, "projectId");
        identity = identity == null ? "" : identity;
        expression = expression == null ? "" : expression;
        alignmentPolicy = alignmentPolicy == null ? "EXACT_TIMESTAMP" : alignmentPolicy;
        analysisVersion = analysisVersion == null ? "1" : analysisVersion;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
