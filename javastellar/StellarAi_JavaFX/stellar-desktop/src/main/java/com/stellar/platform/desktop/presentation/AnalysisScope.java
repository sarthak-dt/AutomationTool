package com.stellar.platform.desktop.presentation;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit Graphs analysis scope. Never encoded as null, {@code "*"}, empty string, or a fake UUID.
 */
public final class AnalysisScope {

    public enum Kind {
        ALL_MEASUREMENTS,
        SINGLE_MEASUREMENT
    }

    private final Kind kind;
    private final AnalysisMeasurementContext measurement;
    private final int measurementCount;

    private AnalysisScope(Kind kind, AnalysisMeasurementContext measurement, int measurementCount) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.measurement = measurement == null ? AnalysisMeasurementContext.none() : measurement;
        this.measurementCount = Math.max(0, measurementCount);
        if (kind == Kind.SINGLE_MEASUREMENT && !this.measurement.isPresent()) {
            throw new IllegalArgumentException("SINGLE_MEASUREMENT requires a physical measurement");
        }
    }

    public static AnalysisScope allMeasurements(int measurementCount) {
        return new AnalysisScope(Kind.ALL_MEASUREMENTS, AnalysisMeasurementContext.none(), measurementCount);
    }

    public static AnalysisScope single(AnalysisMeasurementContext measurement) {
        return new AnalysisScope(Kind.SINGLE_MEASUREMENT, measurement, 1);
    }

    public static AnalysisScope single(String sourceId, String fileName) {
        return single(AnalysisMeasurementContext.of(sourceId, fileName));
    }

    public Kind kind() {
        return kind;
    }

    public boolean isAll() {
        return kind == Kind.ALL_MEASUREMENTS;
    }

    public boolean isSingle() {
        return kind == Kind.SINGLE_MEASUREMENT;
    }

    public Optional<AnalysisMeasurementContext> measurement() {
        return isSingle() ? Optional.of(measurement) : Optional.empty();
    }

    public AnalysisMeasurementContext measurementOrNone() {
        return isSingle() ? measurement : AnalysisMeasurementContext.none();
    }

    public int measurementCount() {
        return measurementCount;
    }

    public String comboLabel() {
        if (isAll()) {
            return MeasurementCatalogScope.allMeasurementsLabel(measurementCount);
        }
        String name = measurement.fileName();
        return name.isBlank() ? measurement.sourceId() : name;
    }

    public String token() {
        if (isAll()) {
            return "ALL|" + measurementCount;
        }
        return "SINGLE|" + measurement.token();
    }

    public String persistKind() {
        return isAll() ? "ALL" : "SINGLE";
    }

    public String persistSourceId() {
        return isSingle() ? measurement.sourceId() : "";
    }

    public String persistFileName() {
        return isSingle() ? measurement.fileName() : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnalysisScope that)) {
            return false;
        }
        return kind == that.kind && measurement.equals(that.measurement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, measurement);
    }

    @Override
    public String toString() {
        return comboLabel();
    }
}
