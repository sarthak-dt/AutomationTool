package com.stellar.platform.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Transport form of {@link com.stellar.platform.domain.processing.plan.ProjectProcessingPlan}.
 * Deterministic mapper: {@link com.stellar.platform.api.support.ProcessingPlanDtoMapper}.
 */
public record ProcessingPlanDto(
        String planId,
        Instant createdAt,
        String readiness,
        List<ProcessingJobDto> jobs,
        List<ProcessingPlanDiagnosticDto> diagnostics
) {
    public ProcessingPlanDto {
        planId = planId == null ? "" : planId;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        readiness = readiness == null ? "EMPTY" : readiness;
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
