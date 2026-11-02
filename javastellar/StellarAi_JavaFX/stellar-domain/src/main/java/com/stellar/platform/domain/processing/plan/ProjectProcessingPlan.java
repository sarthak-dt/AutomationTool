package com.stellar.platform.domain.processing.plan;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable validated execution plan: metadata/selection only.
 * Job order is deterministic display/execution order, not chronological data order.
 */
public final class ProjectProcessingPlan {

    private final String planId;
    private final Instant createdAt;
    private final List<ProcessingJob> jobs;
    private final ProcessingPlanReadiness readiness;
    private final List<ProcessingPlanDiagnostic> diagnostics;

    public ProjectProcessingPlan(
            String planId,
            Instant createdAt,
            List<ProcessingJob> jobs,
            ProcessingPlanReadiness readiness,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        this.planId = planId == null || planId.isBlank() ? UUID.randomUUID().toString() : planId;
        this.createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        this.jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public static ProjectProcessingPlan empty() {
        return new ProjectProcessingPlan(
                UUID.randomUUID().toString(),
                Instant.now(),
                List.of(),
                ProcessingPlanReadiness.EMPTY,
                List.of(ProcessingPlanDiagnostic.of(ProcessingPlanDiagnosticCode.EMPTY_SELECTION))
        );
    }

    public String planId() {
        return planId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<ProcessingJob> jobs() {
        return jobs;
    }

    public ProcessingPlanReadiness readiness() {
        return readiness;
    }

    public List<ProcessingPlanDiagnostic> diagnostics() {
        return diagnostics;
    }

    public ProcessingPlanSummary summary() {
        Set<String> sources = new LinkedHashSet<>();
        long frames = 0L;
        int ready = 0;
        int blocked = 0;
        for (ProcessingJob job : jobs) {
            sources.add(job.projectSourceId());
            frames += job.frameCount();
            if (job.executable()) {
                ready++;
            } else {
                blocked++;
            }
        }
        return new ProcessingPlanSummary(
                sources.size(),
                jobs.size(),
                jobs.size(),
                frames,
                ready,
                blocked
        );
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }
}
