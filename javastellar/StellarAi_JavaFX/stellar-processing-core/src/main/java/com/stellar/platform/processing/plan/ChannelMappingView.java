package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable mapping snapshot for plan building. Produced from existing project mapping state —
 * no DBC reparse.
 */
public final class ChannelMappingView {

    private final SourceChannelRef channel;
    private final MappingLifecycle lifecycle;
    private final String definitionSourceId;
    private final String definitionDisplayName;
    private final long observedUniqueMessageCount;
    private final long matchedUniqueMessageCount;
    private final double coverageRatio;
    private final long observedFrameCount;
    private final long matchedFrameCount;
    private final double frameCoverageRatio;
    private final boolean definitionPresent;

    /** Backward-compatible constructor for existing callers/tests. */
    public ChannelMappingView(
            SourceChannelRef channel,
            MappingLifecycle lifecycle,
            String definitionSourceId,
            String definitionDisplayName,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            double coverageRatio,
            boolean definitionPresent
    ) {
        this(channel, lifecycle, definitionSourceId, definitionDisplayName,
                observedUniqueMessageCount, matchedUniqueMessageCount, coverageRatio,
                0L, 0L, 0.0, definitionPresent);
    }

    public ChannelMappingView(
            SourceChannelRef channel,
            MappingLifecycle lifecycle,
            String definitionSourceId,
            String definitionDisplayName,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            double coverageRatio,
            long observedFrameCount,
            long matchedFrameCount,
            double frameCoverageRatio,
            boolean definitionPresent
    ) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.definitionSourceId = blankToNull(definitionSourceId);
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        this.matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        this.coverageRatio = clamp(coverageRatio);
        this.observedFrameCount = Math.max(0L, observedFrameCount);
        this.matchedFrameCount = Math.min(this.observedFrameCount, Math.max(0L, matchedFrameCount));
        this.frameCoverageRatio = clamp(frameCoverageRatio);
        this.definitionPresent = definitionPresent;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    public SourceChannelRef channel() {
        return channel;
    }

    public MappingLifecycle lifecycle() {
        return lifecycle;
    }

    public Optional<String> definitionSourceId() {
        return Optional.ofNullable(definitionSourceId);
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public long observedUniqueMessageCount() {
        return observedUniqueMessageCount;
    }

    public long matchedUniqueMessageCount() {
        return matchedUniqueMessageCount;
    }

    public double coverageRatio() {
        return coverageRatio;
    }

    public long observedFrameCount() {
        return observedFrameCount;
    }

    public long matchedFrameCount() {
        return matchedFrameCount;
    }

    public double frameCoverageRatio() {
        return frameCoverageRatio;
    }

    public boolean definitionPresent() {
        return definitionPresent;
    }
}
