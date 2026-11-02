package com.stellar.platform.processing;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a streaming measurement → DBC → signal pipeline run.
 * <p>
 * {@link #diagnostics()} returns one representative diagnostic per aggregated category
 * (bounded by unique codes). {@link #aggregatedDiagnostics()} exposes occurrence counts.
 */
public record ProcessingResult(
        ProcessingStatistics statistics,
        List<ProcessingDiagnostic> diagnostics,
        List<AggregatedDiagnostic> aggregatedDiagnostics,
        boolean cancelled,
        boolean completedSuccessfully,
        ProcessingOutcome outcome
) {

    public ProcessingResult {
        Objects.requireNonNull(statistics, "statistics");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        aggregatedDiagnostics = List.copyOf(
                Objects.requireNonNull(aggregatedDiagnostics, "aggregatedDiagnostics"));
        Objects.requireNonNull(outcome, "outcome");
    }

    public ProcessingResult(
            ProcessingStatistics statistics,
            List<ProcessingDiagnostic> diagnostics,
            boolean cancelled,
            boolean completedSuccessfully,
            ProcessingOutcome outcome
    ) {
        this(
                statistics,
                diagnostics,
                AggregatedDiagnostic.aggregate(diagnostics == null ? List.of() : diagnostics),
                cancelled,
                completedSuccessfully,
                outcome
        );
    }

    public ProcessingResult(
            ProcessingStatistics statistics,
            List<ProcessingDiagnostic> diagnostics,
            boolean cancelled,
            boolean completedSuccessfully
    ) {
        this(
                statistics,
                diagnostics,
                cancelled,
                completedSuccessfully,
                cancelled
                        ? ProcessingOutcome.CANCELLED
                        : (completedSuccessfully ? ProcessingOutcome.COMPLETED : ProcessingOutcome.FAILED)
        );
    }
}
