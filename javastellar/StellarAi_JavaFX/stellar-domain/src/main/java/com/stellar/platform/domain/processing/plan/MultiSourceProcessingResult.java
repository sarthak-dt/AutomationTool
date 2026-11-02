package com.stellar.platform.domain.processing.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Project-level container of independent job results.
 * Does NOT merge timestamps, samples, or timelines across jobs.
 */
public final class MultiSourceProcessingResult {

    private final String planId;
    private final Instant createdAt;
    private final ProjectExecutionStatus status;
    private final List<IndependentJobResult> jobResults;
    private final List<ProcessingPlanDiagnostic> diagnostics;
    private final Duration elapsed;

    public MultiSourceProcessingResult(
            String planId,
            Instant createdAt,
            ProjectExecutionStatus status,
            List<IndependentJobResult> jobResults,
            List<ProcessingPlanDiagnostic> diagnostics,
            Duration elapsed
    ) {
        this.planId = planId == null ? "" : planId;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        this.status = Objects.requireNonNull(status, "status");
        this.jobResults = List.copyOf(jobResults == null ? List.of() : jobResults);
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        this.elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }

    public static MultiSourceProcessingResult blocked(
            String planId,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        return new MultiSourceProcessingResult(
                planId,
                Instant.now(),
                ProjectExecutionStatus.BLOCKED,
                List.of(),
                diagnostics,
                Duration.ZERO
        );
    }

    public String planId() {
        return planId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ProjectExecutionStatus status() {
        return status;
    }

    public List<IndependentJobResult> jobResults() {
        return jobResults;
    }

    public List<ProcessingPlanDiagnostic> diagnostics() {
        return diagnostics;
    }

    public Duration elapsed() {
        return elapsed;
    }

    public int requestedJobs() {
        return jobResults.size();
    }

    public int successfulJobs() {
        int n = 0;
        for (IndependentJobResult r : jobResults) {
            if (r.successful()) {
                n++;
            }
        }
        return n;
    }

    public int failedJobs() {
        int n = 0;
        for (IndependentJobResult r : jobResults) {
            if (r.status() == JobExecutionStatus.FAILED || r.status() == JobExecutionStatus.CANCELLED) {
                n++;
            }
        }
        return n;
    }

    public Optional<IndependentJobResult> resultFor(ProcessingJobKey key) {
        if (key == null) {
            return Optional.empty();
        }
        for (IndependentJobResult r : jobResults) {
            if (r.jobKey().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Optional<IndependentJobResult> resultFor(SourceChannelRef channel) {
        if (channel == null) {
            return Optional.empty();
        }
        for (IndependentJobResult r : jobResults) {
            if (r.channel().equals(channel)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public List<IndependentJobResult> successfulResults() {
        List<IndependentJobResult> out = new ArrayList<>();
        for (IndependentJobResult r : jobResults) {
            if (r.successful()) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }
}
