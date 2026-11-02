package com.stellar.platform.parser.spi;

import java.util.List;
import java.util.Objects;

/**
 * Early, format-specific CAN channel hints before full Message-ID indexing completes.
 * Known channel IDs may be empty when only bus-event structures are known.
 */
public record CanChannelDiscoveryResult(
        boolean canStructuresPresent,
        int canBusEventGroupCount,
        List<Integer> knownChannelIds
) {
    public CanChannelDiscoveryResult {
        knownChannelIds = List.copyOf(Objects.requireNonNullElse(knownChannelIds, List.of()));
        if (canBusEventGroupCount < 0) {
            throw new IllegalArgumentException("canBusEventGroupCount must be >= 0");
        }
    }

    public static CanChannelDiscoveryResult none() {
        return new CanChannelDiscoveryResult(false, 0, List.of());
    }

    public static CanChannelDiscoveryResult structuresOnly(int groupCount) {
        return new CanChannelDiscoveryResult(groupCount > 0, Math.max(0, groupCount), List.of());
    }
}
