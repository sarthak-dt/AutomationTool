package com.stellar.platform.processing;

import java.util.List;
import java.util.Objects;

/**
 * Collapses repeated diagnostics into countable rows for UI/logging.
 */
public record AggregatedDiagnostic(
        String layer,
        String code,
        String message,
        long count
) {

    public AggregatedDiagnostic {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
    }

    public static List<AggregatedDiagnostic> aggregate(List<ProcessingDiagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
        for (ProcessingDiagnostic d : diagnostics) {
            accumulator.record(d);
        }
        return accumulator.snapshot();
    }
}
