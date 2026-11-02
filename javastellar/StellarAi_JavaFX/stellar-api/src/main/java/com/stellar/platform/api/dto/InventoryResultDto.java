package com.stellar.platform.api.dto;

import java.util.List;

public record InventoryResultDto(
        String projectId,
        String sourceId,
        String sourcePath,
        String sourceFileName,
        String format,
        String status,
        long totalCanChannels,
        long totalCanFrames,
        long totalUniqueMessageIds,
        boolean channelDiscoveryComplete,
        List<InventoryChannelDto> channels,
        List<String> diagnostics
) {
    public InventoryResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        sourcePath = sourcePath == null ? "" : sourcePath;
        sourceFileName = sourceFileName == null ? "" : sourceFileName;
        format = format == null ? "UNKNOWN" : format;
        status = status == null ? "" : status;
        totalCanChannels = Math.max(0L, totalCanChannels);
        totalCanFrames = Math.max(0L, totalCanFrames);
        totalUniqueMessageIds = Math.max(0L, totalUniqueMessageIds);
        channels = channels == null ? List.of() : List.copyOf(channels);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
