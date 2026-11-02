package com.stellar.platform.desktop.presentation;

/**
 * Compact DBC enrichment status for one observed CAN identity row.
 * Distinct from {@link com.stellar.platform.desktop.project.ChannelMappingStatus} (workflow).
 */
public enum InventoryEnrichmentMatchState {
    MATCHED,
    UNMATCHED,
    NO_DBC,
    AMBIGUOUS;

    public String badgeLabel() {
        return switch (this) {
            case MATCHED -> "MATCHED";
            case UNMATCHED -> "UNMATCHED";
            case NO_DBC -> "NO DBC";
            case AMBIGUOUS -> "AMBIGUOUS";
        };
    }

    public String tooltip() {
        return switch (this) {
            case MATCHED -> "Observed CAN ID is defined in the current mapped/recommended DBC.";
            case UNMATCHED -> "Observed in the measurement source but not defined in the current DBC.";
            case NO_DBC -> "No effective DBC is available for this source channel.";
            case AMBIGUOUS -> "Multiple DBC candidates — no single authoritative definition.";
        };
    }
}
