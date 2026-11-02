package com.stellar.platform.analysis.model;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnalysisResult {

    private final String analysisId;
    private final AnalysisStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final List<SignalCatalogKey> signalKeys;
    private final TimeWindow requestedRange;
    private final TimeWindow effectiveRange;
    private final AlignmentPolicy alignmentPolicy;
    private final String analysisVersion;
    private final List<EngineeringFinding> findings;
    private final List<SignalStatistics> statistics;
    private final Map<String, Double> metrics;
    private final List<AnalysisDiagnostic> diagnostics;
    private final String derivedIdentity;
    private final String message;

    private AnalysisResult(Builder b) {
        this.analysisId = b.analysisId == null ? "" : b.analysisId;
        this.status = Objects.requireNonNullElse(b.status, AnalysisStatus.FAILED);
        this.startedAt = Objects.requireNonNullElseGet(b.startedAt, Instant::now);
        this.completedAt = b.completedAt;
        this.signalKeys = List.copyOf(b.signalKeys);
        this.requestedRange = b.requestedRange == null ? TimeWindow.unbounded() : b.requestedRange;
        this.effectiveRange = b.effectiveRange == null ? requestedRange : b.effectiveRange;
        this.alignmentPolicy = b.alignmentPolicy == null ? AlignmentPolicy.EXACT_TIMESTAMP : b.alignmentPolicy;
        this.analysisVersion = b.analysisVersion == null ? AnalysisVersion.CURRENT : b.analysisVersion;
        this.findings = List.copyOf(b.findings);
        this.statistics = List.copyOf(b.statistics);
        this.metrics = Map.copyOf(new LinkedHashMap<>(b.metrics));
        this.diagnostics = List.copyOf(b.diagnostics);
        this.derivedIdentity = b.derivedIdentity == null ? "" : b.derivedIdentity;
        this.message = b.message == null ? "" : b.message;
    }

    public static Builder builder(String analysisId) {
        return new Builder(analysisId);
    }

    public String analysisId() { return analysisId; }
    public AnalysisStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public List<SignalCatalogKey> signalKeys() { return signalKeys; }
    public TimeWindow requestedRange() { return requestedRange; }
    public TimeWindow effectiveRange() { return effectiveRange; }
    public AlignmentPolicy alignmentPolicy() { return alignmentPolicy; }
    public String analysisVersion() { return analysisVersion; }
    public List<EngineeringFinding> findings() { return findings; }
    public List<SignalStatistics> statistics() { return statistics; }
    public Map<String, Double> metrics() { return metrics; }
    public List<AnalysisDiagnostic> diagnostics() { return diagnostics; }
    public String derivedIdentity() { return derivedIdentity; }
    public String message() { return message; }

    public static final class Builder {
        private final String analysisId;
        private AnalysisStatus status = AnalysisStatus.RUNNING;
        private Instant startedAt = Instant.now();
        private Instant completedAt;
        private final List<SignalCatalogKey> signalKeys = new ArrayList<>();
        private TimeWindow requestedRange = TimeWindow.unbounded();
        private TimeWindow effectiveRange;
        private AlignmentPolicy alignmentPolicy = AlignmentPolicy.EXACT_TIMESTAMP;
        private String analysisVersion = AnalysisVersion.CURRENT;
        private final List<EngineeringFinding> findings = new ArrayList<>();
        private final List<SignalStatistics> statistics = new ArrayList<>();
        private final Map<String, Double> metrics = new LinkedHashMap<>();
        private final List<AnalysisDiagnostic> diagnostics = new ArrayList<>();
        private String derivedIdentity = "";
        private String message = "";

        private Builder(String analysisId) {
            this.analysisId = analysisId;
        }

        public Builder status(AnalysisStatus value) { this.status = value; return this; }
        public Builder startedAt(Instant value) { this.startedAt = value; return this; }
        public Builder completedAt(Instant value) { this.completedAt = value; return this; }
        public Builder signalKeys(List<SignalCatalogKey> keys) {
            this.signalKeys.clear();
            if (keys != null) {
                this.signalKeys.addAll(keys);
            }
            return this;
        }
        public Builder requestedRange(TimeWindow value) { this.requestedRange = value; return this; }
        public Builder effectiveRange(TimeWindow value) { this.effectiveRange = value; return this; }
        public Builder alignmentPolicy(AlignmentPolicy value) { this.alignmentPolicy = value; return this; }
        public Builder analysisVersion(String value) { this.analysisVersion = value; return this; }
        public Builder addFinding(EngineeringFinding finding) {
            if (finding != null) {
                this.findings.add(finding);
            }
            return this;
        }
        public Builder addStatistics(SignalStatistics stats) {
            if (stats != null) {
                this.statistics.add(stats);
            }
            return this;
        }
        public Builder metric(String name, double value) {
            this.metrics.put(name, value);
            return this;
        }
        public Builder addDiagnostic(String code, String message) {
            this.diagnostics.add(new AnalysisDiagnostic(code, message));
            return this;
        }
        public Builder derivedIdentity(String value) { this.derivedIdentity = value; return this; }
        public Builder message(String value) { this.message = value; return this; }

        public AnalysisResult build() {
            return new AnalysisResult(this);
        }
    }
}
