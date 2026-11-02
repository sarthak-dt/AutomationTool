package com.stellar.platform.parser.mf4.schema;

/**
 * CN block schema metadata (no record decode).
 * <p>
 * Composition members are linked from a parent via {@code cn_composition} and appear with
 * {@link #parentChannelAddress()} set; top-level channels use {@code parentChannelAddress == 0}.
 */
public record MdfCnMetadata(
        long address,
        long parentCgAddress,
        long nextCnLink,
        long compositionLink,
        long parentChannelAddress,
        long nameLink,
        long sourceLink,
        long conversionLink,
        long dataLink,
        long unitLink,
        long commentLink,
        int channelType,
        int syncType,
        int dataType,
        long byteOffset,
        int bitOffset,
        long bitCount,
        int flags,
        long invalidationBitPosition,
        String name,
        String unit,
        int sourceType,
        int busType,
        String sourceName,
        String sourcePath,
        com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion conversion
) {

    /** ASAM {@code cn_flags} bit 10: bus event. */
    public static final int FLAG_BUS_EVENT = 0x400;

    public MdfCnMetadata {
        conversion = conversion == null
                ? com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.IDENTITY
                : conversion;
    }

    public boolean compositionMember() {
        return parentChannelAddress != 0L;
    }

    public boolean hasCompositionChildren() {
        return compositionLink != 0L;
    }

    public boolean busEventFlag() {
        return (flags & FLAG_BUS_EVENT) != 0;
    }

    public boolean mimeSampleOrStream() {
        return dataType == 10 || dataType == 11;
    }
}
