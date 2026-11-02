package com.stellar.platform.processing.map.time;

/** What the bytes mean before normalization. Epoch unit is never guessed from digit count. */
public enum TimestampRawKind {
    UNKNOWN,
    EPOCH_NANOS,
    CIVIL_LOCAL,
    ELAPSED,
    ISO_ZONED,
    ISO_UNZONED
}
