package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AdvancedAnalyticsRequest {

    private final String analysisId;
    private final String projectId;
    private final AdvancedAnalyticsType type;
    private final List<SignalCatalogKey> signalKeys;
    private final TimeWindow window;
    private final SampleHandling sampleHandling;
    private final ResampleMethod resampleMethod;
    private final double targetRateHz;
    private final long maxGapNanos;
    private final long alignOriginNanos;
    private final WindowFunction windowFunction;
    private final int fftSize;
    private final int welchSegmentLength;
    private final int welchOverlap;
    private final double minPeakMagnitude;
    private final double minPeakSeparationHz;
    private final double freqMinHz;
    private final double freqMaxHz;
    private final int maxPeaks;
    private final int harmonicCount;
    private final double lowCutoffHz;
    private final double highCutoffHz;
    private final int filterOrder;
    private final FilterPhaseMode filterPhaseMode;
    private final double smoothWindow;
    private final WindowUnit smoothWindowUnit;
    private final double alpha;
    private final double timeConstantSeconds;
    private final EnvelopeMode envelopeMode;
    private final WindowedStatistic windowedStatistic;
    private final CrossingMode crossingMode;
    private final long maxLagNanos;
    private final boolean discreteSignal;
    private final String sourceUnit;
    private final long artifactGeneration;
    private final String sourceFingerprint;
    private final int maxPoints;
    private final boolean persistDerived;

    private AdvancedAnalyticsRequest(Builder b) {
        this.analysisId = b.analysisId == null || b.analysisId.isBlank()
                ? UUID.randomUUID().toString() : b.analysisId;
        this.projectId = b.projectId == null ? "" : b.projectId;
        this.type = Objects.requireNonNull(b.type, "type");
        this.signalKeys = List.copyOf(b.signalKeys);
        this.window = b.window == null ? TimeWindow.unbounded() : b.window;
        this.sampleHandling = b.sampleHandling == null ? SampleHandling.ORIGINAL : b.sampleHandling;
        this.resampleMethod = b.resampleMethod == null ? ResampleMethod.LINEAR_INTERPOLATION : b.resampleMethod;
        this.targetRateHz = b.targetRateHz;
        this.maxGapNanos = b.maxGapNanos;
        this.alignOriginNanos = b.alignOriginNanos;
        this.windowFunction = b.windowFunction == null ? WindowFunction.HANN : b.windowFunction;
        this.fftSize = b.fftSize;
        this.welchSegmentLength = b.welchSegmentLength;
        this.welchOverlap = b.welchOverlap;
        this.minPeakMagnitude = b.minPeakMagnitude;
        this.minPeakSeparationHz = b.minPeakSeparationHz;
        this.freqMinHz = b.freqMinHz;
        this.freqMaxHz = b.freqMaxHz;
        this.maxPeaks = b.maxPeaks;
        this.harmonicCount = b.harmonicCount;
        this.lowCutoffHz = b.lowCutoffHz;
        this.highCutoffHz = b.highCutoffHz;
        this.filterOrder = b.filterOrder;
        this.filterPhaseMode = b.filterPhaseMode == null ? FilterPhaseMode.ZERO_PHASE : b.filterPhaseMode;
        this.smoothWindow = b.smoothWindow;
        this.smoothWindowUnit = b.smoothWindowUnit == null ? WindowUnit.MILLISECONDS : b.smoothWindowUnit;
        this.alpha = b.alpha;
        this.timeConstantSeconds = b.timeConstantSeconds;
        this.envelopeMode = b.envelopeMode == null ? EnvelopeMode.ABS_PEAK : b.envelopeMode;
        this.windowedStatistic = b.windowedStatistic == null ? WindowedStatistic.RMS : b.windowedStatistic;
        this.crossingMode = b.crossingMode == null ? CrossingMode.ALL : b.crossingMode;
        this.maxLagNanos = b.maxLagNanos;
        this.discreteSignal = b.discreteSignal;
        this.sourceUnit = b.sourceUnit == null ? "" : b.sourceUnit;
        this.artifactGeneration = b.artifactGeneration;
        this.sourceFingerprint = b.sourceFingerprint == null ? "" : b.sourceFingerprint;
        this.maxPoints = b.maxPoints <= 0 ? AnalyticsLimits.MAX_ANALYSIS_POINTS : b.maxPoints;
        this.persistDerived = b.persistDerived;
    }

    public static Builder builder(AdvancedAnalyticsType type) {
        return new Builder(type);
    }

    public String analysisId() { return analysisId; }
    public String projectId() { return projectId; }
    public AdvancedAnalyticsType type() { return type; }
    public List<SignalCatalogKey> signalKeys() { return signalKeys; }
    public SignalCatalogKey primaryKey() {
        return signalKeys.isEmpty() ? null : signalKeys.getFirst();
    }
    public TimeWindow window() { return window; }
    public SampleHandling sampleHandling() { return sampleHandling; }
    public ResampleMethod resampleMethod() { return resampleMethod; }
    public double targetRateHz() { return targetRateHz; }
    public long maxGapNanos() { return maxGapNanos; }
    public long alignOriginNanos() { return alignOriginNanos; }
    public WindowFunction windowFunction() { return windowFunction; }
    public int fftSize() { return fftSize; }
    public int welchSegmentLength() { return welchSegmentLength; }
    public int welchOverlap() { return welchOverlap; }
    public double minPeakMagnitude() { return minPeakMagnitude; }
    public double minPeakSeparationHz() { return minPeakSeparationHz; }
    public double freqMinHz() { return freqMinHz; }
    public double freqMaxHz() { return freqMaxHz; }
    public int maxPeaks() { return maxPeaks; }
    public int harmonicCount() { return harmonicCount; }
    public double lowCutoffHz() { return lowCutoffHz; }
    public double highCutoffHz() { return highCutoffHz; }
    public int filterOrder() { return filterOrder; }
    public FilterPhaseMode filterPhaseMode() { return filterPhaseMode; }
    public double smoothWindow() { return smoothWindow; }
    public WindowUnit smoothWindowUnit() { return smoothWindowUnit; }
    public double alpha() { return alpha; }
    public double timeConstantSeconds() { return timeConstantSeconds; }
    public EnvelopeMode envelopeMode() { return envelopeMode; }
    public WindowedStatistic windowedStatistic() { return windowedStatistic; }
    public CrossingMode crossingMode() { return crossingMode; }
    public long maxLagNanos() { return maxLagNanos; }
    public boolean discreteSignal() { return discreteSignal; }
    public String sourceUnit() { return sourceUnit; }
    public long artifactGeneration() { return artifactGeneration; }
    public String sourceFingerprint() { return sourceFingerprint; }
    public int maxPoints() { return maxPoints; }
    public boolean persistDerived() { return persistDerived; }

    public static final class Builder {
        private String analysisId;
        private String projectId;
        private final AdvancedAnalyticsType type;
        private List<SignalCatalogKey> signalKeys = List.of();
        private TimeWindow window;
        private SampleHandling sampleHandling = SampleHandling.ORIGINAL;
        private ResampleMethod resampleMethod = ResampleMethod.LINEAR_INTERPOLATION;
        private double targetRateHz;
        private long maxGapNanos;
        private long alignOriginNanos;
        private WindowFunction windowFunction = WindowFunction.HANN;
        private int fftSize;
        private int welchSegmentLength = 256;
        private int welchOverlap = 128;
        private double minPeakMagnitude;
        private double minPeakSeparationHz;
        private double freqMinHz;
        private double freqMaxHz = Double.POSITIVE_INFINITY;
        private int maxPeaks = 8;
        private int harmonicCount = 4;
        private double lowCutoffHz;
        private double highCutoffHz;
        private int filterOrder = 2;
        private FilterPhaseMode filterPhaseMode = FilterPhaseMode.ZERO_PHASE;
        private double smoothWindow = 100;
        private WindowUnit smoothWindowUnit = WindowUnit.MILLISECONDS;
        private double alpha = 0.2;
        private double timeConstantSeconds;
        private EnvelopeMode envelopeMode = EnvelopeMode.ABS_PEAK;
        private WindowedStatistic windowedStatistic = WindowedStatistic.RMS;
        private CrossingMode crossingMode = CrossingMode.ALL;
        private long maxLagNanos = 500_000_000L;
        private boolean discreteSignal;
        private String sourceUnit = "";
        private long artifactGeneration;
        private String sourceFingerprint = "";
        private int maxPoints = AnalyticsLimits.MAX_ANALYSIS_POINTS;
        private boolean persistDerived;

        private Builder(AdvancedAnalyticsType type) {
            this.type = type;
        }

        public Builder analysisId(String v) { this.analysisId = v; return this; }
        public Builder projectId(String v) { this.projectId = v; return this; }
        public Builder signals(List<SignalCatalogKey> v) { this.signalKeys = v == null ? List.of() : v; return this; }
        public Builder window(TimeWindow v) { this.window = v; return this; }
        public Builder sampleHandling(SampleHandling v) { this.sampleHandling = v; return this; }
        public Builder resampleMethod(ResampleMethod v) { this.resampleMethod = v; return this; }
        public Builder targetRateHz(double v) { this.targetRateHz = v; return this; }
        public Builder maxGapNanos(long v) { this.maxGapNanos = v; return this; }
        public Builder alignOriginNanos(long v) { this.alignOriginNanos = v; return this; }
        public Builder windowFunction(WindowFunction v) { this.windowFunction = v; return this; }
        public Builder fftSize(int v) { this.fftSize = v; return this; }
        public Builder welchSegmentLength(int v) { this.welchSegmentLength = v; return this; }
        public Builder welchOverlap(int v) { this.welchOverlap = v; return this; }
        public Builder minPeakMagnitude(double v) { this.minPeakMagnitude = v; return this; }
        public Builder minPeakSeparationHz(double v) { this.minPeakSeparationHz = v; return this; }
        public Builder freqRange(double min, double max) { this.freqMinHz = min; this.freqMaxHz = max; return this; }
        public Builder maxPeaks(int v) { this.maxPeaks = v; return this; }
        public Builder harmonicCount(int v) { this.harmonicCount = v; return this; }
        public Builder lowCutoffHz(double v) { this.lowCutoffHz = v; return this; }
        public Builder highCutoffHz(double v) { this.highCutoffHz = v; return this; }
        public Builder filterOrder(int v) { this.filterOrder = v; return this; }
        public Builder filterPhaseMode(FilterPhaseMode v) { this.filterPhaseMode = v; return this; }
        public Builder smoothWindow(double size, WindowUnit unit) {
            this.smoothWindow = size;
            this.smoothWindowUnit = unit;
            return this;
        }
        public Builder alpha(double v) { this.alpha = v; return this; }
        public Builder timeConstantSeconds(double v) { this.timeConstantSeconds = v; return this; }
        public Builder envelopeMode(EnvelopeMode v) { this.envelopeMode = v; return this; }
        public Builder windowedStatistic(WindowedStatistic v) { this.windowedStatistic = v; return this; }
        public Builder crossingMode(CrossingMode v) { this.crossingMode = v; return this; }
        public Builder maxLagNanos(long v) { this.maxLagNanos = v; return this; }
        public Builder discreteSignal(boolean v) { this.discreteSignal = v; return this; }
        public Builder sourceUnit(String v) { this.sourceUnit = v; return this; }
        public Builder artifactGeneration(long v) { this.artifactGeneration = v; return this; }
        public Builder sourceFingerprint(String v) { this.sourceFingerprint = v; return this; }
        public Builder maxPoints(int v) { this.maxPoints = v; return this; }
        public Builder persistDerived(boolean v) { this.persistDerived = v; return this; }

        public AdvancedAnalyticsRequest build() {
            return new AdvancedAnalyticsRequest(this);
        }
    }
}
