package com.stellar.platform.api.dto;

import java.time.Instant;
import java.util.Objects;

public record ProjectSummaryDto(
        String projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        int measurementFileCount,
        int definitionFileCount,
        int signalCount,
        String lastProcessingStatus,
        String projectRoot
) {
    public ProjectSummaryDto {
        projectId = require(projectId, "projectId");
        name = name == null || name.isBlank() ? "Untitled Project" : name;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
        measurementFileCount = Math.max(0, measurementFileCount);
        definitionFileCount = Math.max(0, definitionFileCount);
        signalCount = Math.max(0, signalCount);
        lastProcessingStatus = lastProcessingStatus == null ? "" : lastProcessingStatus;
        projectRoot = projectRoot == null ? "" : projectRoot;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
