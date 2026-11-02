package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;

/**
 * Display-only identity text for Signal Browser rows, chips, and headers.
 * Reads the catalog entry as stored. Does not invent units or channel numbers.
 */
final class SignalRowTooltip {

    private SignalRowTooltip() {
    }

    static String text(SignalCatalogEntry entry) {
        if (entry == null) {
            return "";
        }
        String unit = entry.unit() == null || entry.unit().isBlank() ? "\u2014" : entry.unit().trim();
        String channel = SignalChannelLabels.compact(entry);
        String canId = entry.sourceType() == SignalSourceType.MF4_NATIVE
                ? "\u2014"
                : UiFormat.canId(entry.canId());
        return entry.signalName()
                + "\nUnit: " + unit
                + "\nSource: " + sourceLabel(entry.sourceType())
                + "\nChannel: " + channel
                + "\nCAN ID: " + canId;
    }

    static String sourceLabel(SignalSourceType type) {
        if (type == SignalSourceType.DBC_CAN) {
            return "DBC";
        }
        if (type == SignalSourceType.MF4_NATIVE) {
            return "Native";
        }
        if (type == SignalSourceType.ANALYSIS_DERIVED) {
            return "Derived";
        }
        return type == null ? "" : type.name();
    }
}
