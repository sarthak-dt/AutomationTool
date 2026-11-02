package com.stellar.platform.desktop.project;

/**
 * Per-{@link SourceChannelKey} DBC mapping lifecycle for AUTO_WITH_CONFIRMATION (MF-4).
 */
public enum ChannelMappingStatus {
    UNMAPPED,
    RECOMMENDED,
    AMBIGUOUS,
    NO_MATCH,
    NOT_APPLICABLE,
    CONFIRMED,
    MANUAL;

    public String displayLabel() {
        return switch (this) {
            case UNMAPPED -> "Unmapped";
            case RECOMMENDED -> "Recommended";
            case AMBIGUOUS -> "Ambiguous";
            case NO_MATCH -> "No Match";
            case NOT_APPLICABLE -> "Not Applicable";
            case CONFIRMED -> "Confirmed";
            case MANUAL -> "Manual";
        };
    }

    /** Compact table badge text — full static meaning without ellipsis truncation. */
    public String badgeLabel() {
        return switch (this) {
            case UNMAPPED -> "UNMAPPED";
            case RECOMMENDED -> "RECOMMENDED";
            case AMBIGUOUS -> "AMBIGUOUS";
            case NO_MATCH -> "NO MATCH";
            case NOT_APPLICABLE -> "N/A";
            case CONFIRMED -> "CONFIRMED";
            case MANUAL -> "MANUAL";
        };
    }
}
