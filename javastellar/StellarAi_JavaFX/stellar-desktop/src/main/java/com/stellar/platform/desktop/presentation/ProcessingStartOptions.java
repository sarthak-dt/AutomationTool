package com.stellar.platform.desktop.presentation;

/**
 * Controls navigation and result-preservation behaviour for a LOCAL processing run.
 * Does not affect parser/decode semantics.
 */
public final class ProcessingStartOptions {

    private final boolean preserveExistingResultUntilSuccess;
    private final boolean navigateToSignalsOnSuccess;
    private final boolean openProcessingPageOnStart;

    private ProcessingStartOptions(
            boolean preserveExistingResultUntilSuccess,
            boolean navigateToSignalsOnSuccess,
            boolean openProcessingPageOnStart
    ) {
        this.preserveExistingResultUntilSuccess = preserveExistingResultUntilSuccess;
        this.navigateToSignalsOnSuccess = navigateToSignalsOnSuccess;
        this.openProcessingPageOnStart = openProcessingPageOnStart;
    }

    /** Default Project Files sticky / initial process: keep UI free, auto-open Signals on success. */
    public static ProcessingStartOptions primaryWorkflow() {
        return new ProcessingStartOptions(true, true, false);
    }

    /** Signals Change Channels reprocess: keep old result visible until success. */
    public static ProcessingStartOptions reprocessFromSignals() {
        return new ProcessingStartOptions(true, false, false);
    }

    /** Explicit Processing-page centred run (secondary path). */
    public static ProcessingStartOptions openProcessingDetails() {
        return new ProcessingStartOptions(true, false, true);
    }

    public boolean preserveExistingResultUntilSuccess() {
        return preserveExistingResultUntilSuccess;
    }

    public boolean navigateToSignalsOnSuccess() {
        return navigateToSignalsOnSuccess;
    }

    public boolean openProcessingPageOnStart() {
        return openProcessingPageOnStart;
    }
}
