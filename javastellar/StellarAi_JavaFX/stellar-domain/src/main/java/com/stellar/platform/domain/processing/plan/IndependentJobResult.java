package com.stellar.platform.domain.processing.plan;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable independent result for one {@link ProcessingJob}.
 * Does not merge samples/timestamps with any other job.
 * Signal series data lives behind the opaque catalog handle supplied by the executor
 * (not eagerly materialized here).
 */
public final class IndependentJobResult {

    private final ProcessingJobKey jobKey;
    private final String definitionSourceId;
    private final String definitionDisplayName;
    private final MappingLifecycle mappingLifecycle;
    private final JobExecutionStatus status;
    private final long expectedSelectedChannelFrames;
    private final long sourceFramesRead;
    private final long selectedChannelFrames;
    private final long dbcMatchedFrames;
    private final long dbcUnmatchedFrames;
    private final long signalsProduced;
    private final Long firstSampleTimestampNanos;
    private final Long lastSampleTimestampNanos;
    private final ObservedTimingMetadata observedSourceTiming;
    private final Duration elapsed;
    private final List<ProcessingPlanDiagnostic> diagnostics;
    private final String technicalDetail;
    private final Object catalogHandle;

    public IndependentJobResult(
            ProcessingJobKey jobKey,
            String definitionSourceId,
            String definitionDisplayName,
            MappingLifecycle mappingLifecycle,
            JobExecutionStatus status,
            long expectedSelectedChannelFrames,
            long sourceFramesRead,
            long selectedChannelFrames,
            long dbcMatchedFrames,
            long dbcUnmatchedFrames,
            long signalsProduced,
            Long firstSampleTimestampNanos,
            Long lastSampleTimestampNanos,
            ObservedTimingMetadata observedSourceTiming,
            Duration elapsed,
            List<ProcessingPlanDiagnostic> diagnostics,
            String technicalDetail,
            Object catalogHandle
    ) {
        this.jobKey = Objects.requireNonNull(jobKey, "jobKey");
        this.definitionSourceId = blankToNull(definitionSourceId);
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.mappingLifecycle = Objects.requireNonNull(mappingLifecycle, "mappingLifecycle");
        this.status = Objects.requireNonNull(status, "status");
        this.expectedSelectedChannelFrames = Math.max(0L, expectedSelectedChannelFrames);
        this.sourceFramesRead = Math.max(0L, sourceFramesRead);
        this.selectedChannelFrames = Math.max(0L, selectedChannelFrames);
        this.dbcMatchedFrames = Math.max(0L, dbcMatchedFrames);
        this.dbcUnmatchedFrames = Math.max(0L, dbcUnmatchedFrames);
        this.signalsProduced = Math.max(0L, signalsProduced);
        this.firstSampleTimestampNanos = firstSampleTimestampNanos;
        this.lastSampleTimestampNanos = lastSampleTimestampNanos;
        this.observedSourceTiming = observedSourceTiming == null ? null : observedSourceTiming.copy();
        this.elapsed = elapsed == null ? Duration.ZERO : elapsed;
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        this.technicalDetail = technicalDetail == null ? "" : technicalDetail;
        this.catalogHandle = catalogHandle;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ProcessingJobKey jobKey() {
        return jobKey;
    }

    public SourceChannelRef channel() {
        return jobKey.channel();
    }

    public String projectSourceId() {
        return jobKey.projectSourceId();
    }

    public int channelId() {
        return jobKey.channelId();
    }

    public Optional<String> definitionSourceId() {
        return Optional.ofNullable(definitionSourceId);
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public MappingLifecycle mappingLifecycle() {
        return mappingLifecycle;
    }

    public JobExecutionStatus status() {
        return status;
    }

    public long expectedSelectedChannelFrames() {
        return expectedSelectedChannelFrames;
    }

    public long sourceFramesRead() {
        return sourceFramesRead;
    }

    public long selectedChannelFrames() {
        return selectedChannelFrames;
    }

    public long dbcMatchedFrames() {
        return dbcMatchedFrames;
    }

    public long dbcUnmatchedFrames() {
        return dbcUnmatchedFrames;
    }

    public long signalsProduced() {
        return signalsProduced;
    }

    public Optional<Long> firstSampleTimestampNanos() {
        return Optional.ofNullable(firstSampleTimestampNanos);
    }

    public Optional<Long> lastSampleTimestampNanos() {
        return Optional.ofNullable(lastSampleTimestampNanos);
    }

    public Optional<ObservedTimingMetadata> observedSourceTiming() {
        return Optional.ofNullable(observedSourceTiming);
    }

    public Duration elapsed() {
        return elapsed;
    }

    public List<ProcessingPlanDiagnostic> diagnostics() {
        return diagnostics;
    }

    public String technicalDetail() {
        return technicalDetail;
    }

    /**
     * Opaque catalog/series handle from the executor (typically InMemorySignalCatalogStore).
     * May be null on failed jobs.
     */
    public Optional<Object> catalogHandle() {
        return Optional.ofNullable(catalogHandle);
    }

    public boolean successful() {
        return status == JobExecutionStatus.SUCCESS || status == JobExecutionStatus.SUCCESS_WITH_WARNINGS;
    }

    /**
     * In-memory success whose staged store was not promoted. Diagnostics and source identity stay.
     * The rejected store handle is dropped so it cannot be catalogued.
     */
    public IndependentJobResult rejectedPersistence(String reason) {
        String why = reason == null || reason.isBlank() ? "Artifact was not saved." : reason.trim();
        String detail = technicalDetail.isBlank() ? why : technicalDetail + "\n" + why;
        return new IndependentJobResult(
                jobKey,
                definitionSourceId,
                definitionDisplayName,
                mappingLifecycle,
                JobExecutionStatus.FAILED,
                expectedSelectedChannelFrames,
                sourceFramesRead,
                selectedChannelFrames,
                dbcMatchedFrames,
                dbcUnmatchedFrames,
                signalsProduced,
                firstSampleTimestampNanos,
                lastSampleTimestampNanos,
                observedSourceTiming,
                elapsed,
                diagnostics,
                detail,
                null
        );
    }
}
