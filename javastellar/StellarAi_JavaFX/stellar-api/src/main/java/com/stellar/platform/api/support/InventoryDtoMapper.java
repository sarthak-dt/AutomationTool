package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.InventoryChannelDto;
import com.stellar.platform.api.dto.InventoryMessageDto;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.ArrayList;
import java.util.List;

public final class InventoryDtoMapper {

    private InventoryDtoMapper() {
    }

    public static InventoryResultDto toDto(String projectId, String sourceId, CanSourceInventory inventory) {
        List<InventoryChannelDto> channels = new ArrayList<>();
        for (CanChannelInventory ch : inventory.channels()) {
            List<InventoryMessageDto> messages = ch.messages().stream()
                    .map(m -> new InventoryMessageDto(m.canId(), m.extended(), m.frameCount()))
                    .toList();
            channels.add(new InventoryChannelDto(
                    ch.channelId(),
                    ch.displayName(),
                    ch.frameCount(),
                    ch.uniqueMessageCount(),
                    messages
            ));
        }
        List<String> diagnostics = inventory.diagnostics().stream()
                .map(CanInventoryDiagnostic::message)
                .toList();
        return new InventoryResultDto(
                projectId,
                sourceId,
                inventory.sourcePath().toString(),
                inventory.sourceFileName(),
                inventory.format().name(),
                inventory.status().name(),
                inventory.totalCanChannels(),
                inventory.totalCanFrames(),
                inventory.totalUniqueMessageIds(),
                inventory.channelDiscoveryComplete(),
                channels,
                diagnostics
        );
    }
}
