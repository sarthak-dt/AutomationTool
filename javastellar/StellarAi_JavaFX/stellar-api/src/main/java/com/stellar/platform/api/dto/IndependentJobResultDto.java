package com.stellar.platform.api.dto;

import java.util.List;

/** Per-job result without opaque catalog handles (query signals via SignalQueryApi). */
public record IndependentJobResultDto(
        String projectSourceId,
        int channelId,
        String definitionSourceId,
        String definitionDisplayName,
        String mappingLifecycle,
        String status,
        long expectedSelectedChannelFrames,
        long sourceFramesRead,
        long selectedChannelFrames,
        long dbcMatchedFrames,
        long dbcUnmatchedFrames,
        long signalsProduced,
        Long firstSampleTimestampNanos,
        Long lastSampleTimestampNanos,
        ObservedTimingDto observedSourceTiming,
        long elapsedMillis,
        List<ProcessingPlanDiagnosticDto> diagnostics,
        String technicalDetail,
        boolean reused
) {
    public IndependentJobResultDto {
        projectSourceId = projectSourceId == null ? "" : projectSourceId;
        definitionSourceId = definitionSourceId == null ? "" : definitionSourceId;
        definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        mappingLifecycle = mappingLifecycle == null ? "" : mappingLifecycle;
        status = status == null ? "" : status;
        expectedSelectedChannelFrames = Math.max(0L, expectedSelectedChannelFrames);
        sourceFramesRead = Math.max(0L, sourceFramesRead);
        selectedChannelFrames = Math.max(0L, selectedChannelFrames);
        dbcMatchedFrames = Math.max(0L, dbcMatchedFrames);
        dbcUnmatchedFrames = Math.max(0L, dbcUnmatchedFrames);
        signalsProduced = Math.max(0L, signalsProduced);
        elapsedMillis = Math.max(0L, elapsedMillis);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
    }
}
