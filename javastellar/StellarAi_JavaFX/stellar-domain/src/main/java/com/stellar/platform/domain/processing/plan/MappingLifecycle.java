package com.stellar.platform.domain.processing.plan;

/**
 * DBC mapping lifecycle for a source channel as consumed by the processing plan.
 * Presentation labels may differ; this enum is the plan's authoritative state.
 */
public enum MappingLifecycle {
    UNMAPPED,
    RECOMMENDED,
    RECOMMENDED_FALLBACK,
    AMBIGUOUS,
    NO_MATCH,
    NOT_APPLICABLE,
    CONFIRMED,
    MANUAL;

    /** True when the mapping is executable for DBC signal generation. */
    public boolean executable() {
        return this == CONFIRMED || this == MANUAL || this == RECOMMENDED_FALLBACK;
    }

    /**
     * True when a channel with no DBC overlap may process native/raw outputs.
     * The plan builder uses this for both Auto Decode and Custom Decode. It never assigns a DBC.
     */
    public boolean allowsNativeOnlyProcessing() {
        return this == NO_MATCH;
    }

    /** True when a definition may be previewed but must not be treated as confirmed. */
    public boolean previewOnly() {
        return this == RECOMMENDED;
    }

    /**
     * True when the user explicitly accepted this mapping for processing.
     * Recommendations are advisory and are not an accepted processing selection.
     */
    public boolean userAccepted() {
        return this == CONFIRMED || this == MANUAL;
    }

    /** Plan/review label. RECOMMENDED_FALLBACK stays a recommendation, not a confirmation. */
    public String planLabel() {
        return switch (this) {
            case UNMAPPED -> "Unmapped";
            case RECOMMENDED, RECOMMENDED_FALLBACK -> "Recommended";
            case AMBIGUOUS -> "Ambiguous";
            case NO_MATCH -> "No Match";
            case NOT_APPLICABLE -> "Not Applicable";
            case CONFIRMED -> "Confirmed";
            case MANUAL -> "Manual";
        };
    }
}
