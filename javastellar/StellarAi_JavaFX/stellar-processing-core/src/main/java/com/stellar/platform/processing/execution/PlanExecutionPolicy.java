package com.stellar.platform.processing.execution;

/**
 * Controls whether a reviewed processing plan may reuse already-completed compatible jobs.
 */
public enum PlanExecutionPolicy {
    /**
     * Enterprise/default mode: reuse successful unchanged job results and execute only cache misses.
     */
    REUSE_COMPLETED,

    /**
     * Explicit user-requested full reprocessing. Every executable job is decoded again.
     */
    FORCE_REPROCESS
}
