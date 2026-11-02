package com.stellar.platform.analysis.model;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EngineeringFinding(
        String findingId,
        String analysisId,
        FindingType findingType,
        AnalysisSeverity severity,
        List<SignalCatalogKey> signalKeys,
        long startTimestampNanos,
        Long endTimestampNanos,
        String summary,
        String details,
        Map<String, Double> numericMetrics,
        Instant createdAt
) {
    public EngineeringFinding {
        findingId = findingId == null || findingId.isBlank() ? UUID.randomUUID().toString() : findingId;
        analysisId = analysisId == null ? "" : analysisId;
        findingType = Objects.requireNonNull(findingType, "findingType");
        severity = Objects.requireNonNullElse(severity, AnalysisSeverity.OBSERVATION);
        signalKeys = List.copyOf(signalKeys == null ? List.of() : signalKeys);
        summary = summary == null ? "" : summary;
        details = details == null ? "" : details;
        numericMetrics = Map.copyOf(numericMetrics == null ? Map.of() : new LinkedHashMap<>(numericMetrics));
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    public long effectiveEndNanos() {
        return endTimestampNanos == null ? startTimestampNanos : endTimestampNanos;
    }

    public double durationSeconds() {
        return (effectiveEndNanos() - startTimestampNanos) / 1_000_000_000.0d;
    }
}
