package com.stellar.platform.api.dto;

import java.time.Instant;
import java.util.List;

/** Full processing-run outcome for Local/HTTP parity comparison. */
public record ProcessingRunResultDto(
        String projectId,
        String runId,
        String planId,
        String status,
        Instant createdAt,
        long elapsedMillis,
        List<IndependentJobResultDto> jobResults,
        List<ProcessingPlanDiagnosticDto> diagnostics,
        String message
) {
    public ProcessingRunResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        planId = planId == null ? "" : planId;
        status = status == null ? "" : status;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        elapsedMillis = Math.max(0L, elapsedMillis);
        jobResults = jobResults == null ? List.of() : List.copyOf(jobResults);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        message = message == null ? "" : message;
    }
}
