package com.stellar.platform.analysis.model;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AnalysisRequest {

    private final String projectId;
    private final String analysisId;
    private final AnalysisType analysisType;
    private final List<SignalCatalogKey> signalKeys;
    private final TimeRangeSource rangeSource;
    private final TimeWindow requestedWindow;
    private final Long cursorANanos;
    private final Long cursorBNanos;
    private final AlignmentPolicy alignmentPolicy;
    private final AnalysisParameters parameters;
    private final String analysisVersion;
    private final long artifactGeneration;
    private final String sourceFingerprint;
    private final String dbcFingerprint;

    private AnalysisRequest(Builder b) {
        this.projectId = b.projectId == null ? "" : b.projectId;
        this.analysisId = b.analysisId == null || b.analysisId.isBlank() ? UUID.randomUUID().toString() : b.analysisId;
        this.analysisType = Objects.requireNonNull(b.analysisType, "analysisType");
        this.signalKeys = List.copyOf(b.signalKeys);
        this.rangeSource = b.rangeSource == null ? TimeRangeSource.FULL : b.rangeSource;
        this.requestedWindow = b.requestedWindow == null ? TimeWindow.unbounded() : b.requestedWindow;
        this.cursorANanos = b.cursorANanos;
        this.cursorBNanos = b.cursorBNanos;
        this.alignmentPolicy = b.alignmentPolicy == null ? AlignmentPolicy.EXACT_TIMESTAMP : b.alignmentPolicy;
        this.parameters = b.parameters == null ? AnalysisParameters.empty() : b.parameters;
        this.analysisVersion = b.analysisVersion == null || b.analysisVersion.isBlank()
                ? AnalysisVersion.CURRENT : b.analysisVersion;
        this.artifactGeneration = b.artifactGeneration;
        this.sourceFingerprint = b.sourceFingerprint == null ? "" : b.sourceFingerprint;
        this.dbcFingerprint = b.dbcFingerprint == null ? "" : b.dbcFingerprint;
    }

    public static Builder builder(AnalysisType type) {
        return new Builder(type);
    }

    public TimeWindow resolveWindow() {
        return switch (rangeSource) {
            case CURSOR_A_TO_B -> {
                if (cursorANanos == null || cursorBNanos == null) {
                    yield requestedWindow;
                }
                yield TimeWindow.of(cursorANanos, cursorBNanos);
            }
            case FULL -> TimeWindow.unbounded();
            case VIEWPORT, CUSTOM -> requestedWindow;
        };
    }

    public String projectId() { return projectId; }
    public String analysisId() { return analysisId; }
    public AnalysisType analysisType() { return analysisType; }
    public List<SignalCatalogKey> signalKeys() { return signalKeys; }
    public TimeRangeSource rangeSource() { return rangeSource; }
    public TimeWindow requestedWindow() { return requestedWindow; }
    public Long cursorANanos() { return cursorANanos; }
    public Long cursorBNanos() { return cursorBNanos; }
    public AlignmentPolicy alignmentPolicy() { return alignmentPolicy; }
    public AnalysisParameters parameters() { return parameters; }
    public String analysisVersion() { return analysisVersion; }
    public long artifactGeneration() { return artifactGeneration; }
    public String sourceFingerprint() { return sourceFingerprint; }
    public String dbcFingerprint() { return dbcFingerprint; }

    public static final class Builder {
        private String projectId = "";
        private String analysisId;
        private final AnalysisType analysisType;
        private final List<SignalCatalogKey> signalKeys = new ArrayList<>();
        private TimeRangeSource rangeSource = TimeRangeSource.FULL;
        private TimeWindow requestedWindow = TimeWindow.unbounded();
        private Long cursorANanos;
        private Long cursorBNanos;
        private AlignmentPolicy alignmentPolicy = AlignmentPolicy.EXACT_TIMESTAMP;
        private AnalysisParameters parameters = AnalysisParameters.empty();
        private String analysisVersion = AnalysisVersion.CURRENT;
        private long artifactGeneration;
        private String sourceFingerprint = "";
        private String dbcFingerprint = "";

        private Builder(AnalysisType analysisType) {
            this.analysisType = analysisType;
        }

        public Builder projectId(String value) { this.projectId = value; return this; }
        public Builder analysisId(String value) { this.analysisId = value; return this; }
        public Builder signals(List<SignalCatalogKey> keys) {
            this.signalKeys.clear();
            if (keys != null) {
                this.signalKeys.addAll(keys);
            }
            return this;
        }
        public Builder addSignal(SignalCatalogKey key) {
            if (key != null) {
                this.signalKeys.add(key);
            }
            return this;
        }
        public Builder rangeSource(TimeRangeSource value) { this.rangeSource = value; return this; }
        public Builder requestedWindow(TimeWindow value) { this.requestedWindow = value; return this; }
        public Builder cursors(Long a, Long b) { this.cursorANanos = a; this.cursorBNanos = b; return this; }
        public Builder alignmentPolicy(AlignmentPolicy value) { this.alignmentPolicy = value; return this; }
        public Builder parameters(AnalysisParameters value) { this.parameters = value; return this; }
        public Builder artifactGeneration(long value) { this.artifactGeneration = value; return this; }
        public Builder sourceFingerprint(String value) { this.sourceFingerprint = value; return this; }
        public Builder dbcFingerprint(String value) { this.dbcFingerprint = value; return this; }

        public AnalysisRequest build() {
            return new AnalysisRequest(this);
        }
    }
}
