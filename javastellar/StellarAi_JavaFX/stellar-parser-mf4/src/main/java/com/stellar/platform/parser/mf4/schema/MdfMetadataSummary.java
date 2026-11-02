package com.stellar.platform.parser.mf4.schema;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate schema/metadata extracted from the reachable MF4 graph.
 */
public record MdfMetadataSummary(
        List<MdfDgMetadata> dataGroups,
        List<MdfCgMetadata> channelGroups,
        List<MdfCnMetadata> channels,
        List<MdfCaMetadata> channelArrays,
        List<MdfDataBlockMetadata> dataBlocks,
        List<String> unsupportedStructures
) {

    public MdfMetadataSummary {
        dataGroups = List.copyOf(Objects.requireNonNull(dataGroups, "dataGroups"));
        channelGroups = List.copyOf(Objects.requireNonNull(channelGroups, "channelGroups"));
        channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        channelArrays = List.copyOf(Objects.requireNonNull(channelArrays, "channelArrays"));
        dataBlocks = List.copyOf(Objects.requireNonNull(dataBlocks, "dataBlocks"));
        unsupportedStructures = List.copyOf(Objects.requireNonNull(unsupportedStructures, "unsupportedStructures"));
    }

    public long declaredCycleCountTotal() {
        return channelGroups.stream().mapToLong(MdfCgMetadata::cycleCount).sum();
    }
}
