package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

/**
 * Compact / full CAN-channel labels for Signals UI. Native MF4 measurements are not CAN channels.
 */
public final class SignalChannelLabels {

    private SignalChannelLabels() {
    }

    public static String compact(SignalCatalogEntry entry) {
        return entry == null ? "\u2014" : compact(entry.key());
    }

    public static String compact(SignalCatalogKey key) {
        if (key == null || key.sourceType() != SignalSourceType.DBC_CAN) {
            return "\u2014";
        }
        return "Ch " + key.channel();
    }

    public static String full(SignalCatalogEntry entry) {
        return entry == null ? "\u2014" : full(entry.key());
    }

    public static String full(SignalCatalogKey key) {
        if (key == null || key.sourceType() != SignalSourceType.DBC_CAN) {
            return "\u2014";
        }
        return "Channel " + key.channel();
    }
}