package com.stellar.platform.domain.catalog;

import java.util.Locale;
import java.util.Objects;

/**
 * Collision-safe identity for a catalog entry.
 * <p>
 * Distinct engineering signals that share a prefix (e.g. {@code S} vs {@code S01PID0D_VehicleSpeed})
 * must remain separate entries. Identity is never a truncated/normalized name alone.
 * <p>
 * For DBC CAN signals, {@code channel} is the source CAN channel id from
 * {@code CanonicalCanFrame}/{@code CanonicalSignal}. Native MF4 measurements may carry a
 * measurement-channel index; UI must not present that as a CAN channel.
 */
public record SignalCatalogKey(
        String signalName,
        SignalSourceType sourceType,
        String messageName,
        long canId,
        int channel,
        String sourceFile,
        String sourceDatabase
) {

    public SignalCatalogKey {
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName must not be blank");
        }
        Objects.requireNonNull(sourceType, "sourceType");
        if (messageName == null || messageName.isBlank()) {
            throw new IllegalArgumentException("messageName must not be blank");
        }
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0");
        }
        signalName = signalName.trim();
        messageName = messageName.trim();
        sourceFile = sourceFile == null ? "" : sourceFile;
        sourceDatabase = sourceDatabase == null ? "" : sourceDatabase;
    }

    /**
     * Backward-compatible constructor when channel provenance is not bound (defaults to 0).
     */
    public SignalCatalogKey(
            String signalName,
            SignalSourceType sourceType,
            String messageName,
            long canId,
            String sourceFile,
            String sourceDatabase
    ) {
        this(signalName, sourceType, messageName, canId, 0, sourceFile, sourceDatabase);
    }

    /** Backward-compatible constructor when file association is not yet bound. */
    public SignalCatalogKey(
            String signalName,
            SignalSourceType sourceType,
            String messageName,
            long canId
    ) {
        this(signalName, sourceType, messageName, canId, 0, "", "");
    }

    public String searchBlob() {
        return (signalName + ' ' + messageName + ' ' + canId + " 0x"
                + Long.toHexString(canId).toLowerCase(Locale.ROOT)
                + " ch" + channel
                + ' ' + sourceFile + ' ' + sourceDatabase)
                .toLowerCase(Locale.ROOT);
    }
}
