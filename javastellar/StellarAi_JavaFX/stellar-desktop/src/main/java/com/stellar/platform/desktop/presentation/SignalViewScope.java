package com.stellar.platform.desktop.presentation;

/**
 * Explicit Signals workspace scope. Travels with {@link SignalsProcessingContext} so Graphs
 * and navigation preserve project-wide vs source/job catalog boundaries without inferring
 * from display strings.
 */
public enum SignalViewScope {
    /** Explore Signals — all successful jobs, logically merged across measurement files. */
    PROJECT,
    /** View Signals for one measurement source — that source only. */
    SOURCE,
    /** View Signals for one processing job/channel. */
    JOB
}
