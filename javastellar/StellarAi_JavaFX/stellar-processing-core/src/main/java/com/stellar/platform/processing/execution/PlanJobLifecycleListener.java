package com.stellar.platform.processing.execution;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;

/**
 * Optional observer for sequential plan job lifecycle (between jobs; not per-frame).
 */
public interface PlanJobLifecycleListener {

    void onJobStarting(ProcessingJob job, int index, int total);

    void onJobFinished(IndependentJobResult result, int index, int total);

    /**
     * Called when a successful compatible result is reused without reading the measurement source.
     */
    default void onJobReused(ProcessingJob job, IndependentJobResult result, int index, int total) {
    }

    PlanJobLifecycleListener NOOP = new PlanJobLifecycleListener() {
        @Override
        public void onJobStarting(ProcessingJob job, int index, int total) {
        }

        @Override
        public void onJobFinished(IndependentJobResult result, int index, int total) {
        }
    };
}
