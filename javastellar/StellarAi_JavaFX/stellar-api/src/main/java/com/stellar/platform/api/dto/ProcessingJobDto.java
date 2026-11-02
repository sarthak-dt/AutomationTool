package com.stellar.platform.api.dto;

import java.util.List;

/**
 * Transport form of one {@link com.stellar.platform.domain.processing.plan.ProcessingJob}.
 * Does not embed filesystem paths — assets are bound separately on the run request.
 */
public record ProcessingJobDto(
        String projectSourceId,
        int channelId,
        String sourceFormat,
        String mappingLifecycle,
        String definitionSourceId,
        String definitionDisplayName,
        long observedCanIdentityCount,
        long matchedCanIdentityCount,
        long unmatchedCanIdentityCount,
        long frameCount,
        double coverageRatio,
        long expectedDbcMatchedFrameCount,
        double frameCoverageRatio,
        ObservedTimingDto observedTiming,
        boolean executable,
        List<ProcessingPlanDiagnosticDto> diagnostics
) {
    public ProcessingJobDto {
        if (projectSourceId == null || projectSourceId.isBlank()) {
            throw new IllegalArgumentException("projectSourceId must not be blank");
        }
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        sourceFormat = sourceFormat == null ? "UNKNOWN" : sourceFormat;
        mappingLifecycle = mappingLifecycle == null ? "UNMAPPED" : mappingLifecycle;
        definitionSourceId = definitionSourceId == null ? "" : definitionSourceId;
        definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        observedCanIdentityCount = Math.max(0L, observedCanIdentityCount);
        matchedCanIdentityCount = Math.max(0L, matchedCanIdentityCount);
        unmatchedCanIdentityCount = Math.max(0L, unmatchedCanIdentityCount);
        frameCount = Math.max(0L, frameCount);
        expectedDbcMatchedFrameCount = Math.max(0L, expectedDbcMatchedFrameCount);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
