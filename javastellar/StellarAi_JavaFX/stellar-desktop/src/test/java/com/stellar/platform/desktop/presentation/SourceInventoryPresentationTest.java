package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryPresentationTest {

    @Test
    void modelStoresIndependentInventoriesPerFile() {
        SourceInventoryModel model = new SourceInventoryModel();
        model.put("a", inventory("a.mf4", MeasurementSourceFormat.MF4, 1));
        model.put("b", inventory("b.asc", MeasurementSourceFormat.ASC, 2));
        model.put("c", inventory("c.blf", MeasurementSourceFormat.BLF, 4));

        assertEquals(3, model.all().size());
        assertEquals(MeasurementSourceFormat.ASC, model.inventoryFor("b").orElseThrow().format());
        model.focus("c");
        assertEquals("c", model.focusedFileId().orElseThrow());
        assertEquals(MeasurementSourceFormat.BLF, model.focusedInventory().orElseThrow().format());
    }

    @Test
    void inventoryShapeIsFormatAgnostic() {
        for (MeasurementSourceFormat format : List.of(
                MeasurementSourceFormat.MF4,
                MeasurementSourceFormat.ASC,
                MeasurementSourceFormat.BLF
        )) {
            CanSourceInventory inv = inventory("x." + format.name().toLowerCase(), format, 7);
            assertEquals(1, inv.totalCanChannels());
            assertEquals(7, inv.channels().getFirst().channelId());
            assertEquals(1, inv.totalUniqueMessageIds());
            assertTrue(inv.channels().getFirst().messages().getFirst().canId() > 0);
        }
    }

    @Test
    void globalObservedUniqueIsUnionNotSumOfChannels() {
        // Channel A: 1,2,3  Channel B: 3,4,5  → per-channel sum=6, union=5
        CanSourceInventory inventory = multiChannelInventory(
                channel(1, 1, 2, 3),
                channel(2, 3, 4, 5)
        );
        long perChannelSum = inventory.channels().stream()
                .mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        assertEquals(6, perChannelSum);
        assertEquals(5, inventory.globalUniqueCanIdentityCount());
        assertEquals(5, SourceInventoryMatchState.computeGlobalObservedUnique(inventory));
        assertNotEquals(perChannelSum, inventory.globalUniqueCanIdentityCount());
    }

    @Test
    void matchStateNoDbcSelectedByDefault() {
        SourceInventoryModel model = new SourceInventoryModel();
        assertEquals(CanDefinitionMatchStatus.NO_DBC_SELECTED, model.matchState().status());
    }

    @Test
    void waitingStateWhenDbcPresentButInventoryIndexing() {
        SourceInventoryMatchState waiting = SourceInventoryMatchState.waiting("demo.dbc", 0);
        assertEquals(CanDefinitionMatchStatus.WAITING_FOR_SOURCE_INVENTORY, waiting.status());
        assertEquals("demo.dbc", waiting.dbcFileName().orElseThrow());
    }

    @Test
    void matchingServiceResultIsStoredWithoutMutatingInventory() {
        CanSourceInventory inventory = multiChannelInventory(
                channel(1, 1, 2, 3),
                channel(2, 3, 4, 5)
        );
        DbcDatabase dbc = dbc(2, 3, 4);
        CanDefinitionMatchSummary summary = new CanDefinitionMatchingService().match(inventory, dbc);

        SourceInventoryModel model = new SourceInventoryModel();
        model.put("m1", inventory);
        model.setMatchState(SourceInventoryMatchState.ofSummary("x.dbc", summary));

        assertEquals(CanDefinitionMatchStatus.READY, model.matchState().status());
        assertEquals(5, model.matchState().summary().orElseThrow().globalObservedUniqueCount());
        assertEquals(3, model.matchState().summary().orElseThrow().globalMatchedUniqueCount());
        assertSame(inventory, model.focusedInventory().orElseThrow());
        assertEquals(2, inventory.channels().size());
    }

    @Test
    void clearMatchReturnsToNoDbcSelected() {
        SourceInventoryModel model = new SourceInventoryModel();
        model.setMatchState(SourceInventoryMatchState.matching("a.dbc"));
        model.setMatchState(SourceInventoryMatchState.noDbcSelected());
        assertEquals(CanDefinitionMatchStatus.NO_DBC_SELECTED, model.matchState().status());
    }

    private static CanSourceInventory inventory(String name, MeasurementSourceFormat format, int channel) {
        CanMessageInventory message = new CanMessageInventory(
                new CanMessageKey(channel, 0x123, false),
                10,
                8,
                8,
                false
        );
        CanChannelInventory ch = new CanChannelInventory(
                channel, "Channel " + channel, 10, 1, 1, 0, 0, List.of(message));
        return new CanSourceInventory(
                Path.of(name),
                name,
                format,
                CanInventoryStatus.INVENTORY_READY,
                1,
                10,
                1,
                true,
                List.of(ch),
                List.of()
        );
    }

    private static CanSourceInventory multiChannelInventory(CanChannelInventory... channels) {
        List<CanChannelInventory> list = List.of(channels);
        long frames = list.stream().mapToLong(CanChannelInventory::frameCount).sum();
        long uniqueUnion = CanSourceInventory.countGlobalUniqueCanIdentities(list);
        return new CanSourceInventory(
                Path.of("synth.mf4"),
                "synth.mf4",
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                list.size(),
                frames,
                uniqueUnion,
                true,
                list,
                List.of()
        );
    }

    private static CanChannelInventory channel(int channelId, long... canIds) {
        List<CanMessageInventory> messages = new ArrayList<>();
        long frames = 0;
        for (long id : canIds) {
            long count = 2;
            messages.add(new CanMessageInventory(
                    new CanMessageKey(channelId, id, false), count, 8, 8, false));
            frames += count;
        }
        return new CanChannelInventory(
                channelId,
                "Channel " + channelId,
                frames,
                messages.size(),
                messages.size(),
                0,
                0,
                messages
        );
    }

    private static DbcDatabase dbc(long... ids) {
        List<DbcMessage> messages = new ArrayList<>();
        for (long id : ids) {
            messages.add(new DbcMessage(
                    CanIdKey.fromFrame(id, false),
                    "M_" + id,
                    8,
                    "",
                    List.of(),
                    ""
            ));
        }
        return new DbcDatabase("", List.of(), messages, List.of());
    }
}
