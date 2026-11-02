package com.stellar.platform.desktop.presentation;

/**
 * Raised when New Project is attempted without a valid Analysis Storage configuration.
 * Presentation layer maps this to a setup prompt (not a crash dialog).
 */
public final class AnalysisStorageRequiredException extends IllegalStateException {

    public AnalysisStorageRequiredException(String message) {
        super(message == null || message.isBlank()
                ? "Analysis storage must be configured before creating a project."
                : message);
    }
}
