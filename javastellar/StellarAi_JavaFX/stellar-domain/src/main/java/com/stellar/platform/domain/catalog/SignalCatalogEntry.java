package com.stellar.platform.domain.catalog;

import java.util.Objects;
import java.util.Optional;

/**
 * One distinct engineering signal discovered while processing measurement + definition files.
 * Catalog size scales with unique definitions, not sample count.
 */
public record SignalCatalogEntry(
        SignalCatalogKey key,
        String unit,
        long sampleCount,
        Long firstTimestampNanos,
        Long lastTimestampNanos,
        Double minPhysical,
        Double maxPhysical,
        String sourceFile,
        String sourceDatabase
) {

    public SignalCatalogEntry {
        Objects.requireNonNull(key, "key");
        unit = unit == null ? "" : unit;
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0");
        }
        sourceFile = sourceFile == null ? "" : sourceFile;
        sourceDatabase = sourceDatabase == null ? "" : sourceDatabase;
    }

    public String signalName() {
        return key.signalName();
    }

    public SignalSourceType sourceType() {
        return key.sourceType();
    }

    public String messageName() {
        return key.messageName();
    }

    public long canId() {
        return key.canId();
    }

    /** Source CAN channel for DBC signals; measurement channel index for native samples. */
    public int channel() {
        return key.channel();
    }

    public Optional<Long> firstTimestampNanosOptional() {
        return Optional.ofNullable(firstTimestampNanos);
    }

    public Optional<Long> lastTimestampNanosOptional() {
        return Optional.ofNullable(lastTimestampNanos);
    }

    public Optional<Double> minPhysicalOptional() {
        return Optional.ofNullable(minPhysical);
    }

    public Optional<Double> maxPhysicalOptional() {
        return Optional.ofNullable(maxPhysical);
    }
}
