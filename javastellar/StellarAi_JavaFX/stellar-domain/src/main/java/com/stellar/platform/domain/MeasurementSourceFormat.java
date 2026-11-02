package com.stellar.platform.domain;

import java.util.Locale;

/**
 * Explicit measurement source format. Prefer this over ad-hoc filename checks after reader selection.
 */
public enum MeasurementSourceFormat {
    MF4,
    ASC,
    BLF,
    UNKNOWN;

    public static MeasurementSourceFormat fromFormatKey(String formatKey) {
        if (formatKey == null || formatKey.isBlank()) {
            return UNKNOWN;
        }
        String key = formatKey.trim().toLowerCase(Locale.ROOT);
        if (key.contains("mf4") || key.contains("mdf")) {
            return MF4;
        }
        if (key.contains("asc")) {
            return ASC;
        }
        if (key.contains("blf")) {
            return BLF;
        }
        return UNKNOWN;
    }

    public String displayName() {
        return name();
    }

    /**
     * Formats that currently have a production {@code AutomotiveFileReader} for inventory.
     */
    public boolean inventoryReaderAvailable() {
        return this == MF4 || this == ASC || this == BLF;
    }
}
