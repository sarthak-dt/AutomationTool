package com.stellar.platform.domain.inventory;

import com.stellar.platform.domain.MeasurementSourceFormat;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Format-agnostic CAN source inventory for one measurement file.
 * Memory is proportional to unique channels + message IDs, not frame count.
 */
public final class CanSourceInventory {
    private final Path sourcePath;
    private final String sourceFileName;
    private final MeasurementSourceFormat format;
    private final CanInventoryStatus status;
    private final long totalCanChannels;
    private final long totalCanFrames;
    private final long totalUniqueMessageIds;
    private final boolean channelDiscoveryComplete;
    private final List<CanChannelInventory> channels;
    private final List<CanInventoryDiagnostic> diagnostics;

    public CanSourceInventory(
            Path sourcePath,
            String sourceFileName,
            MeasurementSourceFormat format,
            CanInventoryStatus status,
            long totalCanChannels,
            long totalCanFrames,
            long totalUniqueMessageIds,
            boolean channelDiscoveryComplete,
            List<CanChannelInventory> channels,
            List<CanInventoryDiagnostic> diagnostics
    ) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.sourceFileName = sourceFileName == null || sourceFileName.isBlank()
                ? sourcePath.getFileName().toString()
                : sourceFileName;
        this.format = format == null ? MeasurementSourceFormat.UNKNOWN : format;
        this.status = Objects.requireNonNull(status, "status");
        this.totalCanChannels = Math.max(0L, totalCanChannels);
        this.totalCanFrames = Math.max(0L, totalCanFrames);
        this.totalUniqueMessageIds = Math.max(0L, totalUniqueMessageIds);
        this.channelDiscoveryComplete = channelDiscoveryComplete;
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels")).stream()
                .sorted(Comparator.comparingInt(CanChannelInventory::channelId))
                .toList();
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public static CanSourceInventory empty(
            Path sourcePath,
            MeasurementSourceFormat format,
            CanInventoryStatus status
    ) {
        return new CanSourceInventory(
                sourcePath,
                sourcePath.getFileName().toString(),
                format,
                status,
                0,
                0,
                0,
                false,
                List.of(),
                List.of()
        );
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public MeasurementSourceFormat format() {
        return format;
    }

    public CanInventoryStatus status() {
        return status;
    }

    public long totalCanChannels() {
        return totalCanChannels;
    }

    public long totalCanFrames() {
        return totalCanFrames;
    }

    /**
     * Stored source-global unique CAN identity count (union of canId + standard/extended).
     * Prefer {@link #globalUniqueCanIdentityCount()} when channel message lists are authoritative.
     */
    public long totalUniqueMessageIds() {
        return totalUniqueMessageIds;
    }

    /**
     * True source-global unique CAN identity UNION across channels ({@code canId + extended}).
     * Identical IDs observed on multiple channels count once. Per-channel unique counts are unchanged.
     */
    public long globalUniqueCanIdentityCount() {
        long fromChannels = countGlobalUniqueCanIdentities(channels);
        return fromChannels > 0L || channels.isEmpty() ? fromChannels : totalUniqueMessageIds;
    }

    public static long countGlobalUniqueCanIdentities(List<CanChannelInventory> channelInventories) {
        if (channelInventories == null || channelInventories.isEmpty()) {
            return 0L;
        }
        Set<String> identities = new HashSet<>();
        for (CanChannelInventory channel : channelInventories) {
            if (channel == null) {
                continue;
            }
            for (CanMessageInventory message : channel.messages()) {
                identities.add(message.canId() + (message.extended() ? "|E" : "|S"));
            }
        }
        return identities.size();
    }

    public boolean channelDiscoveryComplete() {
        return channelDiscoveryComplete;
    }

    public List<CanChannelInventory> channels() {
        return channels;
    }

    public List<CanInventoryDiagnostic> diagnostics() {
        return diagnostics;
    }
}
