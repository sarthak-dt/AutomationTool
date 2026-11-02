package com.stellar.platform.desktop.presentation;

/**
 * How strongly a Review &amp; Process finding affects the user's ability to start processing.
 * This is a presentation classification of validation the backend already performs; it never
 * decides readiness on its own.
 */
public enum ReviewIssueSeverity {
    /** Processing cannot produce a meaningful result; Start Processing is disabled. */
    BLOCKING("Blocking", "review-issue-blocking"),
    /** Processing can run, but the user should know the outcome will be reduced. */
    WARNING("Warning", "review-issue-warning"),
    /** Context only; no action is required. */
    INFORMATION("Information", "review-issue-information");

    private final String display;
    private final String styleClass;

    ReviewIssueSeverity(String display, String styleClass) {
        this.display = display;
        this.styleClass = styleClass;
    }

    public String display() {
        return display;
    }

    public String styleClass() {
        return styleClass;
    }

    public boolean blocking() {
        return this == BLOCKING;
    }
}
