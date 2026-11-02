package com.stellar.platform.processing.execution;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;

import java.util.Objects;
import java.util.Optional;

/**
 * Matches current plan jobs to existing registry results by stable job identity.
 * Does not read measurement files, parse DBC, or inspect signal/timestamp payloads.
 */
public final class ProcessingPlanResultReconciler {

    private ProcessingPlanResultReconciler() {
    }

    /**
     * A registry result may be reused only when it is the same {@link ProcessingJob}
     * configuration: {@code ProcessingJobKey} (source + channel) and the same
     * effective {@code DefinitionSourceId}.
     */
    public static Optional<IndependentJobResult> reusableResult(
            ProcessingJob job,
            ProcessingResultRegistry registry
    ) {
        if (job == null || registry == null) {
            return Optional.empty();
        }
        Optional<IndependentJobResult> found = registry.get(job.key());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IndependentJobResult result = found.get();
        // Only completed successful output is reusable. A prior failure/cancellation/block must
        // remain eligible for a real retry on the next incremental run.
        if (!result.successful() || !sameProcessingConfiguration(job, result)) {
            return Optional.empty();
        }
        return found;
    }

    public static boolean sameProcessingConfiguration(ProcessingJob job, IndependentJobResult result) {
        if (job == null || result == null) {
            return false;
        }
        if (!job.key().equals(result.jobKey())) {
            return false;
        }
        return Objects.equals(
                job.definitionSourceId().orElse(null),
                result.definitionSourceId().orElse(null));
    }
}
