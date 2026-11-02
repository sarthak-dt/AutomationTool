package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.block.MdfBlockType;

import java.util.List;
import java.util.Objects;

/**
 * Logical ordered data-source description for a Data Group (no payload decode).
 */
public record MdfDataSourceSchema(
        long dataGroupAddress,
        long dataBlockRootAddress,
        MdfBlockType rootType,
        long declaredDataSize,
        long effectiveReadableSize,
        boolean chainHealthy,
        List<MdfDataSourceNode> orderedNodes
) {

    public MdfDataSourceSchema {
        Objects.requireNonNull(rootType, "rootType");
        orderedNodes = List.copyOf(Objects.requireNonNull(orderedNodes, "orderedNodes"));
    }
}
