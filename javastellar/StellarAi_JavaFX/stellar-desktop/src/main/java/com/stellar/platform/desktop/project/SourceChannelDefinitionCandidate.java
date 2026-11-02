package com.stellar.platform.desktop.project;

import java.util.Objects;

/**
 * Immutable match candidate for one SourceChannelKey &times; DefinitionSourceId.
 * Memory scales with unique observed IDs, not frames.
 *
 * <p>Both unique CAN-ID coverage and observed-frame coverage are retained. ID coverage
 * tells an engineer how much of the bus vocabulary a DBC describes, while frame coverage
 * tells how much of the recorded traffic that DBC can actually decode. Keeping both avoids
 * misleading recommendations when a small number of high-frequency IDs dominate a capture.</p>
 */
public final class SourceChannelDefinitionCandidate {

    private final SourceChannelKey sourceChannelKey;
    private final DefinitionSourceId definitionSourceId;
    private final String definitionDisplayName;
    private final int definitionMessageCount;
    private final DefinitionCandidateKind candidateKind;
    private final long observedUniqueMessageCount;
    private final long matchedUniqueMessageCount;
    private final long unmatchedUniqueMessageCount;
    private final double coverageRatio;
    private final long observedFrameCount;
    private final long matchedFrameCount;
    private final double frameCoverageRatio;

    /**
     * Backward-compatible constructor used by existing presentation tests/fixtures.
     * Frame coverage is unknown in this form and therefore reported as zero.
     */
    public SourceChannelDefinitionCandidate(
            SourceChannelKey sourceChannelKey,
            DefinitionSourceId definitionSourceId,
            String definitionDisplayName,
            int definitionMessageCount,
            DefinitionCandidateKind candidateKind,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            long unmatchedUniqueMessageCount,
            double coverageRatio
    ) {
        this(
                sourceChannelKey,
                definitionSourceId,
                definitionDisplayName,
                definitionMessageCount,
                candidateKind,
                observedUniqueMessageCount,
                matchedUniqueMessageCount,
                unmatchedUniqueMessageCount,
                coverageRatio,
                0L,
                0L,
                0.0
        );
    }

    public SourceChannelDefinitionCandidate(
            SourceChannelKey sourceChannelKey,
            DefinitionSourceId definitionSourceId,
            String definitionDisplayName,
            int definitionMessageCount,
            DefinitionCandidateKind candidateKind,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            long unmatchedUniqueMessageCount,
            double coverageRatio,
            long observedFrameCount,
            long matchedFrameCount,
            double frameCoverageRatio
    ) {
        this.sourceChannelKey = Objects.requireNonNull(sourceChannelKey, "sourceChannelKey");
        this.definitionSourceId = Objects.requireNonNull(definitionSourceId, "definitionSourceId");
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.definitionMessageCount = Math.max(0, definitionMessageCount);
        this.candidateKind = Objects.requireNonNull(candidateKind, "candidateKind");
        this.observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        this.matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        this.unmatchedUniqueMessageCount = Math.max(0L, unmatchedUniqueMessageCount);
        this.coverageRatio = clampRatio(coverageRatio);
        this.observedFrameCount = Math.max(0L, observedFrameCount);
        this.matchedFrameCount = Math.min(this.observedFrameCount, Math.max(0L, matchedFrameCount));
        this.frameCoverageRatio = clampRatio(frameCoverageRatio);
    }

    public SourceChannelKey sourceChannelKey() {
        return sourceChannelKey;
    }

    public DefinitionSourceId definitionSourceId() {
        return definitionSourceId;
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public int definitionMessageCount() {
        return definitionMessageCount;
    }

    public DefinitionCandidateKind candidateKind() {
        return candidateKind;
    }

    public long observedUniqueMessageCount() {
        return observedUniqueMessageCount;
    }

    public long matchedUniqueMessageCount() {
        return matchedUniqueMessageCount;
    }

    public long unmatchedUniqueMessageCount() {
        return unmatchedUniqueMessageCount;
    }

    /** Unique CAN-ID coverage in [0,1]. Zero when no observed IDs. */
    public double coverageRatio() {
        return coverageRatio;
    }

    public double coveragePercent() {
        return coverageRatio * 100.0;
    }

    public long observedFrameCount() {
        return observedFrameCount;
    }

    public long matchedFrameCount() {
        return matchedFrameCount;
    }

    /** Recorded-frame coverage in [0,1]. Zero when no observed frames. */
    public double frameCoverageRatio() {
        return frameCoverageRatio;
    }

    public double frameCoveragePercent() {
        return frameCoverageRatio * 100.0;
    }

    public String overlapLabel() {
        return switch (candidateKind) {
            case EMPTY_DEFINITION -> "Definition contains no CAN messages";
            case NO_OVERLAP -> "No CAN-ID overlap detected";
            case HAS_OVERLAP -> "Overlap detected";
        };
    }

    private static double clampRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceChannelDefinitionCandidate that
                && sourceChannelKey.equals(that.sourceChannelKey)
                && definitionSourceId.equals(that.definitionSourceId)
                && definitionMessageCount == that.definitionMessageCount
                && candidateKind == that.candidateKind
                && observedUniqueMessageCount == that.observedUniqueMessageCount
                && matchedUniqueMessageCount == that.matchedUniqueMessageCount
                && unmatchedUniqueMessageCount == that.unmatchedUniqueMessageCount
                && Double.compare(coverageRatio, that.coverageRatio) == 0
                && observedFrameCount == that.observedFrameCount
                && matchedFrameCount == that.matchedFrameCount
                && Double.compare(frameCoverageRatio, that.frameCoverageRatio) == 0
                && definitionDisplayName.equals(that.definitionDisplayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sourceChannelKey,
                definitionSourceId,
                definitionDisplayName,
                definitionMessageCount,
                candidateKind,
                observedUniqueMessageCount,
                matchedUniqueMessageCount,
                unmatchedUniqueMessageCount,
                coverageRatio,
                observedFrameCount,
                matchedFrameCount,
                frameCoverageRatio
        );
    }
}
