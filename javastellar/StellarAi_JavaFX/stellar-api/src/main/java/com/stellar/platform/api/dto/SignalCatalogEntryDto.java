package com.stellar.platform.api.dto;

import java.util.List;

/** Catalog entry metadata only — never embeds sample arrays. */
public record SignalCatalogEntryDto(
        String key,
        String name,
        String unit,
        String messageName,
        String sourceType,
        long canId,
        int channel,
        String sourceFile,
        String sourceDatabase,
        long sampleCount,
        Long firstTimestampNanos,
        Long lastTimestampNanos,
        Double minPhysical,
        Double maxPhysical
) {
    public SignalCatalogEntryDto {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        name = name == null ? key : name;
        unit = unit == null ? "" : unit;
        messageName = messageName == null ? "" : messageName;
        sourceType = sourceType == null ? "" : sourceType;
        sourceFile = sourceFile == null ? "" : sourceFile;
        sourceDatabase = sourceDatabase == null ? "" : sourceDatabase;
        sampleCount = Math.max(0L, sampleCount);
        channel = Math.max(0, channel);
    }

    /** Backward-compatible compact constructor used by older callers/tests. */
    public SignalCatalogEntryDto(
            String key,
            String name,
            String unit,
            String messageName,
            String sourceType,
            long sampleCount
    ) {
        this(key, name, unit, messageName, sourceType, 0L, 0, "", "", sampleCount,
                null, null, null, null);
    }
}
