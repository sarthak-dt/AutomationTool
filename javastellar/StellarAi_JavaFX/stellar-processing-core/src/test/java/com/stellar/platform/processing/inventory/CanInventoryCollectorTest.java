package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanInventoryCollectorTest {

    @Test
    void oneChannelOneId() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        collector.accept(frame(0, 1, 0x123, false, 8, 8));
        collector.accept(frame(1, 1, 0x123, false, 8, 8));
        collector.setStatus(CanInventoryStatus.INVENTORY_READY);
        collector.markChannelDiscoveryComplete();
        CanSourceInventory inv = collector.snapshot();
        assertEquals(1, inv.totalCanChannels());
        assertEquals(2, inv.totalCanFrames());
        assertEquals(1, inv.totalUniqueMessageIds());
        assertEquals(0x123L, inv.channels().getFirst().messages().getFirst().canId());
    }

    @Test
    void oneChannelMultipleIds() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        collector.accept(frame(0, 1, 0x100, false, 8, 8));
        collector.accept(frame(1, 1, 0x200, false, 8, 8));
        collector.accept(frame(2, 1, 0x100, false, 8, 8));
        CanSourceInventory inv = collector.snapshot();
        assertEquals(1, inv.totalCanChannels());
        assertEquals(2, inv.totalUniqueMessageIds());
        assertEquals(3, inv.totalCanFrames());
    }

    @Test
    void multipleChannels() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.mf4"), MeasurementSourceFormat.MF4);
        collector.accept(frame(0, 1, 0x10, false, 8, 8));
        collector.accept(frame(1, 2, 0x20, false, 8, 8));
        collector.accept(frame(2, 5, 0x30, false, 8, 8));
        assertEquals(3, collector.snapshot().totalCanChannels());
    }

    @Test
    void sameCanIdOnDifferentChannelsCountsOnceInSourceGlobalUnion() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        collector.accept(frame(0, 1, 0x123, false, 8, 8));
        collector.accept(frame(1, 2, 0x123, false, 8, 8));
        CanSourceInventory inv = collector.snapshot();
        assertEquals(2, inv.totalCanChannels());
        assertEquals(1, inv.channels().get(0).uniqueMessageCount());
        assertEquals(1, inv.channels().get(1).uniqueMessageCount());
        assertEquals(1, inv.totalUniqueMessageIds());
        assertEquals(1, inv.globalUniqueCanIdentityCount());
    }

    @Test
    void overlappingIdsAcrossChannelsAreUnionNotSum() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("u.mf4"), MeasurementSourceFormat.MF4);
        // Ch1: 1,2,3  Ch2: 3,4,5 → per-channel sum 6, global union 5
        collector.accept(frame(0, 1, 1, false, 8, 8));
        collector.accept(frame(1, 1, 2, false, 8, 8));
        collector.accept(frame(2, 1, 3, false, 8, 8));
        collector.accept(frame(3, 2, 3, false, 8, 8));
        collector.accept(frame(4, 2, 4, false, 8, 8));
        collector.accept(frame(5, 2, 5, false, 8, 8));
        CanSourceInventory inv = collector.snapshot();
        assertEquals(3, inv.channels().get(0).uniqueMessageCount());
        assertEquals(3, inv.channels().get(1).uniqueMessageCount());
        assertEquals(5, inv.totalUniqueMessageIds());
        assertEquals(5, inv.globalUniqueCanIdentityCount());
    }

    @Test
    void standardAndExtendedSameNumericIdAreDistinct() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        collector.accept(frame(0, 1, 0x123, false, 8, 8));
        collector.accept(frame(1, 1, 0x123, true, 8, 8));
        CanChannelInventory channel = collector.snapshot().channels().getFirst();
        assertEquals(2, channel.uniqueMessageCount());
        assertEquals(1, channel.standardMessageCount());
        assertEquals(1, channel.extendedMessageCount());
    }

    @Test
    void canFdObservationFromDlcAndPayload() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        collector.accept(frame(0, 1, 0x456, false, 16, 16));
        CanMessageInventory message = collector.snapshot().channels().getFirst().messages().getFirst();
        assertTrue(message.canFdObserved());
        assertEquals(16, message.maxObservedDlc());
        assertEquals(1, collector.snapshot().channels().getFirst().canFdFrameCount());
    }

    @Test
    void occurrenceCountsAccumulate() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("a.asc"), MeasurementSourceFormat.ASC);
        for (int i = 0; i < 50; i++) {
            collector.accept(frame(i, 1, 0x10, false, 8, 8));
        }
        assertEquals(50, collector.snapshot().channels().getFirst().messages().getFirst().frameCount());
    }

    @Test
    void emptyStreamProducesZeroChannels() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("empty.asc"), MeasurementSourceFormat.ASC);
        collector.markChannelDiscoveryComplete();
        collector.setStatus(CanInventoryStatus.INVENTORY_READY);
        CanSourceInventory inv = collector.snapshot();
        assertEquals(0, inv.totalCanChannels());
        assertEquals(0, inv.totalCanFrames());
        assertTrue(inv.channels().isEmpty());
    }

    @Test
    void largeSyntheticStreamKeepsBoundedInventorySize() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("big.blf"), MeasurementSourceFormat.BLF);
        int uniqueIds = 40;
        int frames = 200_000;
        for (int i = 0; i < frames; i++) {
            long id = i % uniqueIds;
            int channel = (i % 3) + 1;
            collector.accept(frame(i, channel, id, false, 8, 8));
        }
        CanSourceInventory inv = collector.snapshot();
        assertEquals(frames, inv.totalCanFrames());
        assertEquals(3, inv.totalCanChannels());
        assertEquals(uniqueIds, inv.totalUniqueMessageIds());
        assertEquals(uniqueIds, inv.globalUniqueCanIdentityCount());
        // Per-channel still retain channel-local unique observations (sum can exceed global union).
        long perChannelSum = inv.channels().stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        assertEquals(uniqueIds * 3L, perChannelSum);
        assertTrue(inv.totalUniqueMessageIds() < frames / 100);
    }

    @Test
    void blfProvenanceUsesSameCollectorWithoutFormatAssumptions() {
        CanInventoryCollector collector = new CanInventoryCollector(Path.of("x.blf"), MeasurementSourceFormat.BLF);
        collector.accept(frame(0, 4, 0x18FF50E5L, true, 8, 8));
        CanSourceInventory inv = collector.snapshot();
        assertEquals(MeasurementSourceFormat.BLF, inv.format());
        assertEquals(1, inv.totalCanChannels());
        assertEquals(4, inv.channels().getFirst().channelId());
        assertFalse(inv.format() == MeasurementSourceFormat.MF4);
        assertFalse(inv.format() == MeasurementSourceFormat.ASC);
    }

    private static CanonicalCanFrame frame(
            long seq, int channel, long canId, boolean extended, int dlc, int payloadLen
    ) {
        byte[] data = new byte[payloadLen];
        return new CanonicalCanFrame(
                seq,
                seq * 1_000_000L,
                channel,
                canId,
                extended,
                FrameDirection.RX,
                dlc,
                data,
                FrameQuality.OK,
                SourceReference.ofLine(seq + 1)
        );
    }
}
