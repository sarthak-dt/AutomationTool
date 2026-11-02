package com.stellar.platform.processing;

/**
 * Optional callbacks for streaming decode progress. Implementations must be thread-safe
 * or marshal updates to the UI thread themselves.
 */
public interface ProcessingProgressListener {

    ProcessingProgressListener NOOP = new ProcessingProgressListener() {
    };

    default void onStage(ProcessingStage stage) {
    }

    default void onStatistics(ProcessingStatistics statistics) {
    }
}
