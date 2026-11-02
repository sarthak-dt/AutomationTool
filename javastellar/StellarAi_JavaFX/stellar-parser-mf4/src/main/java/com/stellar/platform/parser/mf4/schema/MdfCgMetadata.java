package com.stellar.platform.parser.mf4.schema;

/**
 * CG block schema metadata (no record decode).
 */
public record MdfCgMetadata(
        long address,
        long parentDgAddress,
        long nextCgLink,
        long firstCnLink,
        long acquisitionNameLink,
        long acquisitionSourceLink,
        long commentLink,
        long recordId,
        long cycleCount,
        int flags,
        long dataBytesPerRecord,
        long invalidationBytesPerRecord,
        int sourceType,
        int busType,
        String sourceName,
        String sourcePath
) {

    /** ASAM {@code cg_flags} bit 0: VLSD channel group. */
    public static final int FLAG_VLSD = 0x01;
    /** ASAM {@code cg_flags} bit 1: bus event channel group. */
    public static final int FLAG_BUS_EVENT = 0x02;
    /** ASAM {@code cg_flags} bit 2: plain bus event channel group. */
    public static final int FLAG_PLAIN_BUS_EVENT = 0x04;

    public MdfCgMetadata {
        sourceName = sourceName == null ? "" : sourceName;
        sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public long totalBytesPerRecord() {
        return dataBytesPerRecord + invalidationBytesPerRecord;
    }

    public boolean vlsdChannelGroup() {
        return (flags & FLAG_VLSD) != 0;
    }

    public boolean busEventChannelGroup() {
        return (flags & FLAG_BUS_EVENT) != 0 || (flags & FLAG_PLAIN_BUS_EVENT) != 0;
    }
}
