package com.stellar.platform.api.dto;

import java.util.Map;

/**
 * Start a multi-job processing run from a canonical plan DTO.
 * Asset maps resolve source/definition ids to STEP-2 file assets — never desktop paths.
 */
public record ProcessingRunRequest(
        ProcessingPlanDto plan,
        String executionPolicy,
        Map<String, String> measurementAssetBySourceId,
        Map<String, String> definitionAssetByDefinitionId,
        int batchSize
) {
    public ProcessingRunRequest {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        executionPolicy = executionPolicy == null || executionPolicy.isBlank()
                ? "REUSE_COMPLETED"
                : executionPolicy.trim();
        measurementAssetBySourceId = measurementAssetBySourceId == null
                ? Map.of()
                : Map.copyOf(measurementAssetBySourceId);
        definitionAssetByDefinitionId = definitionAssetByDefinitionId == null
                ? Map.of()
                : Map.copyOf(definitionAssetByDefinitionId);
        batchSize = batchSize <= 0 ? 4096 : batchSize;
    }
}
