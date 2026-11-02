package com.stellar.platform.api.dto;

import java.util.List;

public record ProcessResultDto(
        String projectId,
        String jobId,
        String measurementAssetId,
        String definitionAssetId,
        boolean completedSuccessfully,
        boolean cancelled,
        String outcome,
        long framesProcessed,
        long signalsProduced,
        int catalogSignalCount,
        List<String> diagnostics,
        String message
) {
    public ProcessResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        measurementAssetId = measurementAssetId == null ? "" : measurementAssetId;
        definitionAssetId = definitionAssetId == null ? "" : definitionAssetId;
        outcome = outcome == null ? "" : outcome;
        framesProcessed = Math.max(0L, framesProcessed);
        signalsProduced = Math.max(0L, signalsProduced);
        catalogSignalCount = Math.max(0, catalogSignalCount);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        message = message == null ? "" : message;
    }
}
