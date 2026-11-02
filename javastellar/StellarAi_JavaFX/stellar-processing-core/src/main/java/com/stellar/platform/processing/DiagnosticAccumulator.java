package com.stellar.platform.processing;

import com.stellar.platform.domain.SourceReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming diagnostic aggregator. Memory scales with unique (layer, code) categories,
 * not with record count.
 */
public final class DiagnosticAccumulator {

    private final Map<String, Mutable> byKey = new LinkedHashMap<>();
    private long totalOccurrences;

    public void record(String layer, String code, String message, SourceReference source) {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(code, "code");
        String msg = message == null || message.isBlank() ? code : message;
        SourceReference src = source == null ? new SourceReference(-1L, -1L) : source;
        String key = layer + '\0' + code;
        Mutable existing = byKey.get(key);
        if (existing == null) {
            byKey.put(key, new Mutable(layer, code, stabilizeMessage(code, msg), src, src, 1L));
        } else {
            existing.count++;
            existing.lastSource = src;
            // Keep first representative message; do not grow key cardinality with per-frame text.
        }
        totalOccurrences++;
    }

    public void record(ProcessingDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        record(diagnostic.layer(), diagnostic.code(), diagnostic.message(), diagnostic.source());
    }

    public long uniqueCategories() {
        return byKey.size();
    }

    public long totalOccurrences() {
        return totalOccurrences;
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    public boolean anyMatch(java.util.function.Predicate<AggregatedDiagnostic> predicate) {
        return snapshot().stream().anyMatch(predicate);
    }

    public List<AggregatedDiagnostic> snapshot() {
        List<AggregatedDiagnostic> out = new ArrayList<>(byKey.size());
        for (Mutable m : byKey.values()) {
            out.add(new AggregatedDiagnostic(m.layer, m.code, m.message, m.count));
        }
        return List.copyOf(out);
    }

    /**
     * One representative {@link ProcessingDiagnostic} per category (for outcome classification).
     */
    public List<ProcessingDiagnostic> representatives() {
        List<ProcessingDiagnostic> out = new ArrayList<>(byKey.size());
        for (Mutable m : byKey.values()) {
            out.add(new ProcessingDiagnostic(m.layer, m.code, m.message, m.firstSource));
        }
        return List.copyOf(out);
    }

    /**
     * Collapse per-frame message text (e.g. specific CAN IDs) into a stable template by code.
     */
    static String stabilizeMessage(String code, String message) {
        if (code == null) {
            return message;
        }
        String upper = code.toUpperCase();
        if (upper.contains("UNMATCHED")) {
            return "One or more CAN frames had no matching DBC message";
        }
        if (upper.contains("DBC_NO_MATCHING_CAN_IDS") || upper.equals("DBC_NO_MATCHING_CAN_IDS")) {
            return "The selected DBC does not contain definitions for CAN IDs present in this recording.";
        }
        if (upper.contains("RECOVERED_RECORD") || upper.equals("RECOVERED_RECORD")) {
            return "Recovered record(s) from physical MF4 data";
        }
        if (upper.contains("NON_CAN")) {
            return "Non-CAN Channel Group record(s) skipped";
        }
        if (upper.contains("VLSD") && upper.contains("SKIP")) {
            return "VLSD Channel Group record(s) skipped for positioning";
        }
        if (upper.contains("ZERO_DECLARED")) {
            return "declaredCycleCount=0 with physical data; schema usable";
        }
        if (upper.contains("NO_VALID_RECORDS")) {
            return "Recovery inspected a physical region but found no structurally valid CAN records";
        }
        if (upper.contains("OUT_OF_RANGE")) {
            return "One or more decoded physical values lie outside the DBC-defined min/max range";
        }
        // Strip volatile hex/decimal suffixes that explode cardinality
        if (message != null && message.length() > 120) {
            return message.substring(0, 117) + "…";
        }
        return message;
    }

    /**
     * High-volume expected conditions — counted in the aggregator / statistics, not as
     * one {@code warnings++} per record.
     */
    public static boolean isHighVolumeInformational(String code) {
        if (code == null) {
            return false;
        }
        String upper = code.toUpperCase();
        return upper.contains("UNMATCHED")
                || upper.contains("MUX_NO_ACTIVE_LEAF")
                || upper.contains("RECOVERED_RECORD")
                || upper.contains("RECOVERY_STARTED")
                || upper.contains("RECOVERY_COMPLETED")
                || upper.contains("RECOVERY_REQUIRED")
                || upper.contains("ZERO_DECLARED")
                || upper.contains("NO_VALID_RECORDS")
                || upper.contains("EFFECTIVE_BOUNDARY")
                || upper.contains("VLSD_RECORD_SKIPPED")
                || upper.contains("NON_CAN")
                || upper.contains("PARTIAL_RECOVERY")
                || upper.contains("OUT_OF_RANGE");
    }

    private static final class Mutable {
        final String layer;
        final String code;
        final String message;
        final SourceReference firstSource;
        SourceReference lastSource;
        long count;

        Mutable(
                String layer,
                String code,
                String message,
                SourceReference firstSource,
                SourceReference lastSource,
                long count
        ) {
            this.layer = layer;
            this.code = code;
            this.message = message;
            this.firstSource = firstSource;
            this.lastSource = lastSource;
            this.count = count;
        }
    }
}
