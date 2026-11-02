package com.stellar.platform.parser.mf4.dynamicschema;

import java.util.List;
import java.util.Objects;

/**
 * Runtime record schema for one Channel Group — describes how records must be decoded.
 */
public record MdfRecordSchema(
        long dataGroupAddress,
        long channelGroupAddress,
        long recordId,
        int recordIdSize,
        long dataBytesPerRecord,
        long invalidationBytesPerRecord,
        long completeRecordLength,
        long declaredCycleCount,
        MdfSchemaStatus status,
        MdfDataSourceSchema dataSource,
        List<MdfChannelFieldSchema> channelFields,
        boolean physicalDataExists,
        boolean vlsdChannelGroup,
        boolean busEventChannelGroup,
        MdfBusKind busKind
) {

    public MdfRecordSchema {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(dataSource, "dataSource");
        channelFields = List.copyOf(Objects.requireNonNull(channelFields, "channelFields"));
        Objects.requireNonNull(busKind, "busKind");
    }

    public boolean usable() {
        return status != MdfSchemaStatus.UNUSABLE;
    }

    /**
     * Fixed-length records only. VLSD CGs use a length-prefixed layout at read time.
     */
    public boolean fixedLengthRecord() {
        return !vlsdChannelGroup;
    }

    public boolean emitsCanonicalCanFrames() {
        return MdfBusEventClassifier.emitsCanonicalCanFrames(busKind);
    }
}
