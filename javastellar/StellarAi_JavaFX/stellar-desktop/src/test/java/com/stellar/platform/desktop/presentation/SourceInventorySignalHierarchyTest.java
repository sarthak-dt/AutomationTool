package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventorySignalHierarchyTest {

    private final CanDefinitionMatchingService matchingService = new CanDefinitionMatchingService();

    @Test
    void matchedMessageExposesItsDbcSignals() {
        DbcDatabase dbc = database(
                message(0x201, false, "Msg201",
                        plain("VehicleSpeed", 0, 16),
                        plain("Signal_B", 16, 8))
        );
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 0x201, false, 10))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "demo.dbc");

        assertTrue(state.messageMatch(1, 0x201, false).orElseThrow().matched());
        assertEquals(2, state.signalDefinitions(0x201, false).size());
        assertEquals("VehicleSpeed", state.signalDefinitions(0x201, false).getFirst().name());
    }

    @Test
    void unmatchedMessageExposesNoSignals() {
        DbcDatabase dbc = database(message(0x201, false, "Msg201", plain("A", 0, 8)));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, msg(1, 0x205, false, 3))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "demo.dbc");

        assertFalse(state.messageMatch(1, 0x205, false).orElseThrow().matched());
        assertTrue(state.signalDefinitions(0x205, false).isEmpty());
        assertTrue(state.dbcMessage(0x205, false).isEmpty());
    }

    @Test
    void messageWithMultipleSignalsAndZeroSignals() {
        DbcDatabase dbc = database(
                message(0x100, false, "Multi",
                        plain("S1", 0, 8),
                        plain("S2", 8, 8),
                        plain("S3", 16, 8)),
                message(0x101, false, "Empty")
        );
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.BLF,
                channel(1, msg(1, 0x100, false, 1), msg(1, 0x101, false, 1))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "x.dbc");

        assertEquals(3, state.signalDefinitions(0x100, false).size());
        assertTrue(state.signalDefinitions(0x101, false).isEmpty());
        assertTrue(state.dbcMessage(0x101, false).isPresent());
    }

    @Test
    void multiplexedSignalMetadataPreserved() {
        DbcSignal mux = new DbcSignal(
                "PID", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 255, "", List.of(), DbcMultiplexRole.MULTIPLEXOR, null, "", DbcValueTable.empty()
        );
        DbcSignal leaf = new DbcSignal(
                "CoolantTemp", 8, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, -40, -40, 215, "C", List.of(), DbcMultiplexRole.MULTIPLEXED, 5, "", DbcValueTable.empty()
        );
        DbcDatabase dbc = database(message(0x7E8, false, "OBD", mux, leaf));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 0x7E8, false, 2))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "obd.dbc");
        List<DbcSignal> signals = state.signalDefinitions(0x7E8, false);

        assertEquals("Multiplexer", DbcSignalDefinitionFormatter.multiplexLabel(signals.get(0)));
        assertEquals("Mux = 5", DbcSignalDefinitionFormatter.multiplexLabel(signals.get(1)));
    }

    @Test
    void standardVersusExtendedIdentityRemainsCorrect() {
        DbcDatabase dbc = database(
                message(0x123, false, "StdOnly", plain("A", 0, 8)),
                message(0x123, true, "ExtOnly", plain("B", 0, 8))
        );
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, msg(1, 0x123, false, 1), msg(1, 0x123, true, 1))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "id.dbc");

        assertEquals("StdOnly", state.dbcMessage(0x123, false).orElseThrow().name());
        assertEquals("ExtOnly", state.dbcMessage(0x123, true).orElseThrow().name());
        assertEquals("A", state.signalDefinitions(0x123, false).getFirst().name());
        assertEquals("B", state.signalDefinitions(0x123, true).getFirst().name());
    }

    @Test
    void sameCanIdOnMultipleChannelsSharesDbcDefinition() {
        DbcDatabase dbc = database(message(0x200, false, "Shared", plain("X", 0, 8)));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 0x200, false, 5)),
                channel(2, msg(2, 0x200, false, 7))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "shared.dbc");

        assertEquals(1, state.signalDefinitions(0x200, false).size());
        assertTrue(state.messageMatch(1, 0x200, false).orElseThrow().matched());
        assertTrue(state.messageMatch(2, 0x200, false).orElseThrow().matched());
        assertEquals(1, state.summary().orElseThrow().globalMatchedUniqueCount());
    }

    @Test
    void dbcChangeRefreshesSignalChildren() {
        DbcDatabase dbcA = database(message(0x10, false, "A", plain("OldSig", 0, 8)));
        DbcDatabase dbcB = database(message(0x10, false, "B",
                plain("NewSig1", 0, 8),
                plain("NewSig2", 8, 8)));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, msg(1, 0x10, false, 1))
        );
        SourceInventoryModel model = new SourceInventoryModel();
        model.put("f1", inventory);
        model.setMatchState(match(inventory, dbcA, "a.dbc"));
        assertEquals(List.of("OldSig"),
                model.matchState().signalDefinitions(0x10, false).stream().map(DbcSignal::name).toList());

        model.setMatchState(match(inventory, dbcB, "b.dbc"));
        assertEquals(List.of("NewSig1", "NewSig2"),
                model.matchState().signalDefinitions(0x10, false).stream().map(DbcSignal::name).toList());
    }

    @Test
    void dbcRemovalRemovesDbcChildrenButKeepsRawInventory() {
        DbcDatabase dbc = database(message(0x11, false, "M", plain("S", 0, 8)));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 0x11, false, 4))
        );
        SourceInventoryModel model = new SourceInventoryModel();
        model.put("f1", inventory);
        model.setMatchState(match(inventory, dbc, "m.dbc"));
        assertFalse(model.matchState().signalDefinitions(0x11, false).isEmpty());

        model.setMatchState(SourceInventoryMatchState.noDbcSelected());
        assertEquals(CanDefinitionMatchStatus.NO_DBC_SELECTED, model.matchState().status());
        assertTrue(model.matchState().signalDefinitions(0x11, false).isEmpty());
        assertTrue(model.matchState().dbcDatabase().isEmpty());
        assertEquals(1, model.focusedInventory().orElseThrow().channels().size());
        assertEquals(0x11, model.focusedInventory().orElseThrow().channels().getFirst().messages().getFirst().canId());
    }

    @Test
    void hierarchyLogicIsFormatIndependent() {
        DbcDatabase dbc = database(message(0x55, false, "F", plain("Sig", 0, 8)));
        for (MeasurementSourceFormat format : List.of(
                MeasurementSourceFormat.MF4,
                MeasurementSourceFormat.ASC,
                MeasurementSourceFormat.BLF
        )) {
            CanSourceInventory inventory = inventory(format, channel(1, msg(1, 0x55, false, 1)));
            SourceInventoryMatchState state = match(inventory, dbc, "f.dbc");
            assertEquals(1, state.signalDefinitions(0x55, false).size(), format.name());
            assertEquals(1, state.summary().orElseThrow().globalMatchedUniqueCount(), format.name());
        }
    }

    @Test
    void expandingSignalHierarchyDoesNotRequireSourceReparse() {
        DbcDatabase dbc = database(message(0x30, false, "M", plain("S", 0, 8)));
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 0x30, false, 9))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "m.dbc");
        // Repeated definition lookups use the cached DbcDatabase reference only.
        assertEquals(dbc, state.dbcDatabase().orElseThrow());
        assertEquals(1, state.signalDefinitions(0x30, false).size());
        assertEquals(1, state.signalDefinitions(0x30, false).size());
        assertEquals(9, inventory.channels().getFirst().messages().getFirst().frameCount());
    }

    @Test
    void existingMatchingCountsRemainUnchangedWithSignalAccess() {
        // Channel A: 1,2,3  Channel B: 3,4,5  DBC: 2,3,4
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, msg(1, 1, false, 1), msg(1, 2, false, 1), msg(1, 3, false, 1)),
                channel(2, msg(2, 3, false, 1), msg(2, 4, false, 1), msg(2, 5, false, 1))
        );
        DbcDatabase dbc = database(
                message(2, false, "M2", plain("A", 0, 8)),
                message(3, false, "M3", plain("B", 0, 8)),
                message(4, false, "M4", plain("C", 0, 8))
        );
        SourceInventoryMatchState state = match(inventory, dbc, "c.dbc");
        CanDefinitionMatchSummary summary = state.summary().orElseThrow();

        assertEquals(5, summary.globalObservedUniqueCount());
        assertEquals(3, summary.globalMatchedUniqueCount());
        assertEquals(2, summary.perChannelResults().get(0).matchedCount());
        assertEquals(2, summary.perChannelResults().get(1).matchedCount());
        assertEquals(1, state.signalDefinitions(2, false).size());
    }

    private SourceInventoryMatchState match(
            CanSourceInventory inventory,
            DbcDatabase dbc,
            String name
    ) {
        CanDefinitionMatchSummary summary = matchingService.match(inventory, dbc);
        return SourceInventoryMatchState.ofSummary(name, summary, dbc);
    }

    private static CanSourceInventory inventory(
            MeasurementSourceFormat format,
            CanChannelInventory... channels
    ) {
        List<CanChannelInventory> list = List.of(channels);
        long frames = list.stream().mapToLong(CanChannelInventory::frameCount).sum();
        long uniqueSum = list.stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        return new CanSourceInventory(
                Path.of("synth." + format.name().toLowerCase()),
                "synth." + format.name().toLowerCase(),
                format,
                CanInventoryStatus.INVENTORY_READY,
                list.size(),
                frames,
                uniqueSum,
                true,
                list,
                List.of()
        );
    }

    private static CanChannelInventory channel(int channelId, CanMessageInventory... messages) {
        List<CanMessageInventory> list = List.of(messages);
        long frames = list.stream().mapToLong(CanMessageInventory::frameCount).sum();
        long standard = list.stream().filter(m -> !m.extended()).count();
        long extended = list.stream().filter(CanMessageInventory::extended).count();
        return new CanChannelInventory(
                channelId,
                "Channel " + channelId,
                frames,
                list.size(),
                standard,
                extended,
                0,
                list
        );
    }

    private static CanMessageInventory msg(int channel, long canId, boolean extended, long frames) {
        return new CanMessageInventory(new CanMessageKey(channel, canId, extended), frames, 8, 8, false);
    }

    private static DbcDatabase database(DbcMessage... messages) {
        return new DbcDatabase("", List.of(), List.of(messages), List.of());
    }

    private static DbcMessage message(long canId, boolean extended, String name, DbcSignal... signals) {
        return new DbcMessage(
                CanIdKey.fromFrame(canId, extended),
                name,
                8,
                "",
                List.of(signals),
                ""
        );
    }

    private static DbcSignal plain(String name, int start, int len) {
        return new DbcSignal(
                name, start, len, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 100, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()
        );
    }
}
