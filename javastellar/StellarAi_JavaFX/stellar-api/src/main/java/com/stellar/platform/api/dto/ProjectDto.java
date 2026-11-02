package com.stellar.platform.api.dto;

import java.time.Instant;
import java.util.Objects;

public record ProjectDto(
        String projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String lastWorkspace,
        String projectRoot
) {
    public ProjectDto {
        projectId = require(projectId, "projectId");
        name = name == null || name.isBlank() ? "Untitled Project" : name;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
        lastWorkspace = lastWorkspace == null ? "" : lastWorkspace;
        projectRoot = projectRoot == null ? "" : projectRoot;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
