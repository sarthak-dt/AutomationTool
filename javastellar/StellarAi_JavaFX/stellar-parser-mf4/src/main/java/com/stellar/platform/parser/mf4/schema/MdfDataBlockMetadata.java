package com.stellar.platform.parser.mf4.schema;

import com.stellar.platform.parser.mf4.block.MdfBlockType;

/**
 * Metadata for DT/DZ/DL/HL/SD data-related blocks (no payload decode).
 */
public record MdfDataBlockMetadata(
        MdfBlockType blockType,
        long address,
        long blockEnd,
        long declaredBlockLength,
        long declaredPayloadLength,
        long physicallyReadableLength,
        long bytesAfterBlockEnd,
        Long nextDlLink,
        Integer dlFlags,
        Integer dlCount,
        Long hlFirstDlLink,
        Integer hlZipType,
        Integer dzZipType,
        Long dzOriginalUncompressedLength,
        Long dzCompressedLength,
        String dzOriginalBlockType
) {
}
