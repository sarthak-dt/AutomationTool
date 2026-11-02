package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/** Lightweight project record persisted independently of measurement samples. */
public record ProjectMetadata(
        String projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String lastWorkspace,
        Instant lastSavedAt
) {
    public ProjectMetadata(String projectId, String name, Instant createdAt, Instant updatedAt) {
        this(projectId, name, createdAt, updatedAt, "", null);
    }

    public ProjectMetadata(String projectId, String name, Instant createdAt, Instant updatedAt, String lastWorkspace) {
        this(projectId, name, createdAt, updatedAt, lastWorkspace, null);
    }

    public ProjectMetadata {
        projectId = requireText(projectId, "projectId");
        name = name == null || name.isBlank() ? "Untitled Project" : name;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
        lastWorkspace = lastWorkspace == null ? "" : lastWorkspace.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
