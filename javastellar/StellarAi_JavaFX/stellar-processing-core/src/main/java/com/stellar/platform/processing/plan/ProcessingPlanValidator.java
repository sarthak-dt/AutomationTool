package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;

import java.util.List;
import java.util.Objects;

/**
 * Classifies readiness of an already-built plan. Does not parse sources or DBC files.
 */
public final class ProcessingPlanValidator {

    public ProcessingPlanReadiness validate(ProjectProcessingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return classify(plan.jobs());
    }

    public ProcessingPlanReadiness classify(List<ProcessingJob> jobs) {
        Objects.requireNonNull(jobs, "jobs");
        if (jobs.isEmpty()) {
            return ProcessingPlanReadiness.EMPTY;
        }
        boolean anyBlockedHard = false;
        boolean anyRecommended = false;
        boolean allExecutable = true;
        for (ProcessingJob job : jobs) {
            if (job.executable()) {
                continue;
            }
            allExecutable = false;
            if (job.mappingLifecycle().previewOnly()
                    && job.diagnostics().stream().noneMatch(d ->
                    d.code() == ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE
                            || d.code() == ProcessingPlanDiagnosticCode.SOURCE_NOT_READY
                            || d.code() == ProcessingPlanDiagnosticCode.CHANNEL_NOT_AVAILABLE
                            || d.code() == ProcessingPlanDiagnosticCode.SOURCE_REMOVED)) {
                anyRecommended = true;
            } else {
                anyBlockedHard = true;
            }
        }
        if (allExecutable) {
            return ProcessingPlanReadiness.READY;
        }
        if (anyBlockedHard) {
            return ProcessingPlanReadiness.INVALID_SELECTION;
        }
        if (anyRecommended) {
            return ProcessingPlanReadiness.REQUIRES_CONFIRMATION;
        }
        return ProcessingPlanReadiness.INVALID_SELECTION;
    }
}
