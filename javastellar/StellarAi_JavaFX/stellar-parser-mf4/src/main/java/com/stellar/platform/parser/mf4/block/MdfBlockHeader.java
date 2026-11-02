package com.stellar.platform.parser.mf4.block;

import java.util.List;
import java.util.Objects;

/**
 * Generic MDF 4 common block header plus resolved link list.
 */
public record MdfBlockHeader(
        long address,
        MdfBlockType type,
        String rawId,
        int reserved,
        long declaredLength,
        long linkCount,
        List<Long> links,
        long blockEnd,
        long dataSectionOffset,
        long declaredDataLength
) {

    public static final int COMMON_HEADER_SIZE = 24;

    public MdfBlockHeader {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawId, "rawId");
        links = List.copyOf(Objects.requireNonNull(links, "links"));
    }

    public boolean isUnknownType() {
        return type == MdfBlockType.UNKNOWN;
    }
}
