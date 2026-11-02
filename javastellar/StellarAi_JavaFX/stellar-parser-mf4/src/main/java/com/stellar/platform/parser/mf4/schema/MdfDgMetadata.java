package com.stellar.platform.parser.mf4.schema;

/**
 * DG block schema metadata (no record decode).
 */
public record MdfDgMetadata(
        long address,
        long nextDgLink,
        long firstCgLink,
        long dataBlockLink,
        long commentLink,
        int recordIdSize
) {
}
