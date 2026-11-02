package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.InventoryChannelDto;
import com.stellar.platform.api.dto.InventoryMessageDto;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Rebuilds domain inventory snapshots from transport DTOs (REMOTE path). */
public final class InventoryDomainMapper {

    private InventoryDomainMapper() {
    }

    public static CanSourceInventory toDomain(InventoryResultDto dto) {
        List<CanChannelInventory> channels = new ArrayList<>();
        for (InventoryChannelDto ch : dto.channels()) {
            List<CanMessageInventory> messages = new ArrayList<>();
            for (InventoryMessageDto msg : ch.messages()) {
                messages.add(new CanMessageInventory(
                        new CanMessageKey(ch.channelId(), msg.canId(), msg.extended()),
                        msg.frameCount(),
                        0,
                        0,
                        false
                ));
            }
            channels.add(new CanChannelInventory(
                    ch.channelId(),
                    ch.displayName(),
                    ch.frameCount(),
                    ch.uniqueMessageCount(),
                    0L,
                    0L,
                    0L,
                    messages
            ));
        }
        MeasurementSourceFormat format;
        try {
            format = MeasurementSourceFormat.valueOf(dto.format());
        } catch (RuntimeException e) {
            format = MeasurementSourceFormat.UNKNOWN;
        }
        CanInventoryStatus status;
        try {
            status = CanInventoryStatus.valueOf(dto.status());
        } catch (RuntimeException e) {
            status = CanInventoryStatus.FAILED;
        }
        Path path = Path.of(dto.sourcePath().isBlank() ? dto.sourceFileName() : dto.sourcePath());
        return new CanSourceInventory(
                path,
                dto.sourceFileName(),
                format,
                status,
                dto.totalCanChannels(),
                dto.totalCanFrames(),
                dto.totalUniqueMessageIds(),
                dto.channelDiscoveryComplete(),
                channels,
                List.of()
        );
    }
}
