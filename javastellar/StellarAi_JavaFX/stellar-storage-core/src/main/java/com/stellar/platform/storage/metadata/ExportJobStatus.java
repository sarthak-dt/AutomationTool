package com.stellar.platform.storage.metadata;

import java.util.Locale;

/** Lifecycle of one Export Center job. */
public enum ExportJobStatus {
    QUEUED,
    EXPORTING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public static ExportJobStatus parse(String value, ExportJobStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return fallback;
        }
    }
}
