package com.stellar.platform.desktop.presentation;

/**
 * User-facing measurement source status for the Sources workspace.
 *
 * <p>This is a presentation vocabulary only. It never replaces {@code FileValidationStatus},
 * {@code ProjectInventoryJobState} or any processing state — {@link SourceStatusResolver}
 * translates those authoritative states into the five labels an engineer sees.</p>
 */
public enum SourceStatus {

    /** Lightweight inspection of the source is queued or running. */
    READING("Reading", "source-status-reading"),

    /** Source was inspected successfully and can continue to Decode. */
    READY("Ready", "source-status-ready"),

    /** Source was kept in the project but could not be inspected. */
    NEEDS_ATTENTION("Needs Attention", "source-status-attention"),

    /** Source file is no longer present at its recorded location. */
    UNAVAILABLE("Unavailable", "source-status-unavailable"),

    /** Source already produced processed signal artifacts in this project. */
    PROCESSED("Processed", "source-status-processed");

    private final String displayLabel;
    private final String styleClass;

    SourceStatus(String displayLabel, String styleClass) {
        this.displayLabel = displayLabel;
        this.styleClass = styleClass;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public String styleClass() {
        return styleClass;
    }

    /** True when this source can take part in the Decode stage. */
    public boolean usableForDecode() {
        return this == READY || this == PROCESSED;
    }

    /** True when the engineer must act before this source can be used. */
    public boolean requiresAction() {
        return this == NEEDS_ATTENTION || this == UNAVAILABLE;
    }
}
