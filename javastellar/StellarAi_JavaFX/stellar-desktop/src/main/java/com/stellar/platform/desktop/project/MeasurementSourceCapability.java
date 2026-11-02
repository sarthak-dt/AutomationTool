package com.stellar.platform.desktop.project;

/**
 * Authoritative processing policy for a product source type.
 * DAT and LOG are direct sources. Detecting MDF, ASC, or BLF inside them does not
 * change that policy. MF4, ASC, and BLF keep their existing definition requirements.
 */
public final class MeasurementSourceCapability {

    private MeasurementSourceCapability() {
    }

    public static boolean requiresDefinition(FileKind kind) {
        return !supportsDirectProcessing(kind);
    }

    public static boolean supportsDirectProcessing(FileKind kind) {
        return kind == FileKind.MEASUREMENT_DAT || kind == FileKind.MEASUREMENT_LOG;
    }
}
