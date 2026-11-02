package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.block.MdfBlockType;

import java.util.Objects;

/**
 * One node in a resolved DG data-source chain (DT/DZ/DL/HL). No payload is loaded.
 */
public record MdfDataSourceNode(
        MdfBlockType blockType,
        long address,
        long declaredPayloadLength,
        long physicallyReadableLength,
        Long nextLink,
        Integer zipType,
        Long originalUncompressedLength,
        Long compressedLength
) {

    public MdfDataSourceNode {
        Objects.requireNonNull(blockType, "blockType");
    }
}
