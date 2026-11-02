package com.stellar.platform.domain.processing.plan;

import com.stellar.platform.domain.MeasurementSourceFormat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata instruction for decoding one SourceChannelKey.
 * Does not contain frames, samples, or decoded signals.
 */
public final class ProcessingJob {

    private final ProcessingJobKey key;
    private final MeasurementSourceFormat sourceFormat;
    private final MappingLifecycle mappingLifecycle;
    private final String definitionSourceId;
    private final String definitionDisplayName;
    private final long observedCanIdentityCount;
    private final long matchedCanIdentityCount;
    private final long unmatchedCanIdentityCount;
    private final long frameCount;
    private final double coverageRatio;
    private final long expectedDbcMatchedFrameCount;
    private final double frameCoverageRatio;
    private final ObservedTimingMetadata observedTiming;
    private final boolean executable;
    private final List<ProcessingPlanDiagnostic> diagnostics;

    public ProcessingJob(
            ProcessingJobKey key,
            MeasurementSourceFormat sourceFormat,
            MappingLifecycle mappingLifecycle,
            String definitionSourceId,
            String definitionDisplayName,
            long observedCanIdentityCount,
            long matchedCanIdentityCount,
            long unmatchedCanIdentityCount,
            long frameCount,
            double coverageRatio,
            ObservedTimingMetadata observedTiming,
            boolean executable,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        this(key, sourceFormat, mappingLifecycle, definitionSourceId, definitionDisplayName,
                observedCanIdentityCount, matchedCanIdentityCount, unmatchedCanIdentityCount,
                frameCount, coverageRatio, 0L, 0.0, observedTiming, executable, diagnostics);
    }

    public ProcessingJob(
            ProcessingJobKey key,
            MeasurementSourceFormat sourceFormat,
            MappingLifecycle mappingLifecycle,
            String definitionSourceId,
            String definitionDisplayName,
            long observedCanIdentityCount,
            long matchedCanIdentityCount,
            long unmatchedCanIdentityCount,
            long frameCount,
            double coverageRatio,
            long expectedDbcMatchedFrameCount,
            double frameCoverageRatio,
            ObservedTimingMetadata observedTiming,
            boolean executable,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.sourceFormat = sourceFormat == null ? MeasurementSourceFormat.UNKNOWN : sourceFormat;
        this.mappingLifecycle = Objects.requireNonNull(mappingLifecycle, "mappingLifecycle");
        this.definitionSourceId = definitionSourceId == null || definitionSourceId.isBlank()
                ? null : definitionSourceId.trim();
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.observedCanIdentityCount = Math.max(0L, observedCanIdentityCount);
        this.matchedCanIdentityCount = Math.max(0L, matchedCanIdentityCount);
        this.unmatchedCanIdentityCount = Math.max(0L, unmatchedCanIdentityCount);
        this.frameCount = Math.max(0L, frameCount);
        this.coverageRatio = coverageRatio;
        this.expectedDbcMatchedFrameCount = Math.min(this.frameCount, Math.max(0L, expectedDbcMatchedFrameCount));
        this.frameCoverageRatio = Double.isFinite(frameCoverageRatio)
                ? Math.max(0.0, Math.min(1.0, frameCoverageRatio)) : 0.0;
        this.observedTiming = observedTiming == null ? null : observedTiming.copy();
        this.executable = executable;
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public ProcessingJobKey key() {
        return key;
    }

    public SourceChannelRef channel() {
        return key.channel();
    }

    public String projectSourceId() {
        return key.projectSourceId();
    }

    public int channelId() {
        return key.channelId();
    }

    public MeasurementSourceFormat sourceFormat() {
        return sourceFormat;
    }

    public MappingLifecycle mappingLifecycle() {
        return mappingLifecycle;
    }

    public Optional<String> definitionSourceId() {
        return Optional.ofNullable(definitionSourceId);
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public long observedCanIdentityCount() {
        return observedCanIdentityCount;
    }

    public long matchedCanIdentityCount() {
        return matchedCanIdentityCount;
    }

    public long unmatchedCanIdentityCount() {
        return unmatchedCanIdentityCount;
    }

    public long frameCount() {
        return frameCount;
    }

    public double coverageRatio() {
        return coverageRatio;
    }

    /** Expected matched frames from the already-built CAN inventory/DBC comparison. */
    public long expectedDbcMatchedFrameCount() {
        return expectedDbcMatchedFrameCount;
    }

    public double frameCoverageRatio() {
        return frameCoverageRatio;
    }

    public Optional<ObservedTimingMetadata> observedTiming() {
        return Optional.ofNullable(observedTiming);
    }

    public boolean executable() {
        return executable;
    }

    public List<ProcessingPlanDiagnostic> diagnostics() {
        return diagnostics;
    }
}
