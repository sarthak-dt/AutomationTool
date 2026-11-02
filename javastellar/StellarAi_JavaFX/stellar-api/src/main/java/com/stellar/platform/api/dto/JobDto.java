package com.stellar.platform.api.dto;

import java.time.Instant;
import java.util.Objects;

public record JobDto(
        String jobId,
        String projectId,
        String kind,
        JobStatusDto status,
        String message,
        Instant createdAt,
        Instant updatedAt,
        Double progressFraction
) {
    public JobDto {
        jobId = require(jobId, "jobId");
        projectId = require(projectId, "projectId");
        kind = kind == null ? "" : kind;
        status = Objects.requireNonNullElse(status, JobStatusDto.QUEUED);
        message = message == null ? "" : message;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
