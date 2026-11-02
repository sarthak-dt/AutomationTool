package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredChannel;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredMessage;
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
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryFilterTest {

    private final CanDefinitionMatchingService matchingService = new CanDefinitionMatchingService();
    private final SourceInventoryFilterEngine engine = new SourceInventoryFilterEngine();

    @Test
    void canIdTextSearch() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withSearchText("0x201")
        );
        assertEquals(1, result.visibleMessageCount());
        assertEquals(0x201, result.channels().getFirst().messages().getFirst().message().canId());
    }

    @Test
    void dbcMessageNameSearch() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withSearchText("_201h_IS_DYN_VCU")
        );
        assertEquals(1, result.visibleMessageCount());
        assertEquals(
                "_201h_IS_DYN_VCU",
                result.channels().getFirst().messages().getFirst().messageMatch().dbcMessageName().orElseThrow());
    }

    @Test
    void signalNameSearchPreservesAncestry() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withSearchText("DIAG_MUX_ON")
        );
        assertEquals(1, result.channels().size());
        assertEquals(1, result.channels().getFirst().channel().channelId());
        FilteredMessage msg = result.channels().getFirst().messages().getFirst();
        assertEquals(0x201, msg.message().canId());
        assertEquals(1, msg.visibleSignals().size());
        assertEquals("DIAG_MUX_ON", msg.visibleSignals().getFirst().name());
    }

    @Test
    void searchIsCaseInsensitiveAndWhitespaceTolerant() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withSearchText("  diag_mux_on  ")
        );
        assertEquals(1, result.visibleMessageCount());
        assertEquals("DIAG_MUX_ON",
                result.channels().getFirst().messages().getFirst().visibleSignals().getFirst().name());
    }

    @Test
    void channelFilter() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withChannelId(OptionalInt.of(2))
        );
        assertEquals(1, result.channels().size());
        assertEquals(2, result.channels().getFirst().channel().channelId());
        assertEquals(2, result.visibleMessageCount());
    }

    @Test
    void matchedFilter() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.MATCHED)
        );
        assertTrue(result.visibleMessageCount() > 0);
        assertEquals(result.visibleMessageCount(), result.visibleMatchedCount());
        for (FilteredChannel ch : result.channels()) {
            for (FilteredMessage m : ch.messages()) {
                assertTrue(m.messageMatch().matched());
            }
        }
    }

    @Test
    void unmatchedFilter() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.UNMATCHED)
        );
        assertTrue(result.visibleMessageCount() > 0);
        assertEquals(0, result.visibleMatchedCount());
        for (FilteredChannel ch : result.channels()) {
            for (FilteredMessage m : ch.messages()) {
                assertFalse(m.messageMatch().matched());
            }
        }
    }

    @Test
    void standardAndExtendedFilters() {
        Fixture fx = fixture();
        SourceInventoryFilterResult std = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withCanTypeFilter(CanTypeFilter.STANDARD)
        );
        assertTrue(std.channels().stream()
                .flatMap(c -> c.messages().stream())
                .noneMatch(m -> m.message().extended()));

        SourceInventoryFilterResult ext = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withCanTypeFilter(CanTypeFilter.EXTENDED)
        );
        assertTrue(ext.visibleMessageCount() >= 1);
        assertTrue(ext.channels().stream()
                .flatMap(c -> c.messages().stream())
                .allMatch(m -> m.message().extended()));
    }

    @Test
    void canFdFilterUsesObservedMetadata() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withCanTypeFilter(CanTypeFilter.CAN_FD)
        );
        assertEquals(1, result.visibleMessageCount());
        assertTrue(result.channels().getFirst().messages().getFirst().message().canFdObserved());
    }

    @Test
    void contentFilters() {
        Fixture fx = fixture();
        SourceInventoryFilterResult messagesOnly = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withContentFilter(ContentFilter.MESSAGES_ONLY)
        );
        assertTrue(messagesOnly.channels().stream()
                .flatMap(c -> c.messages().stream())
                .noneMatch(FilteredMessage::showSignalChildren));

        SourceInventoryFilterResult withSignals = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withContentFilter(ContentFilter.MESSAGES_WITH_SIGNALS)
        );
        assertTrue(withSignals.visibleMessageCount() > 0);
        assertTrue(withSignals.channels().stream()
                .flatMap(c -> c.messages().stream())
                .allMatch(m -> !m.visibleSignals().isEmpty()));

        SourceInventoryFilterResult signalsOnly = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withContentFilter(ContentFilter.SIGNALS_ONLY)
        );
        assertTrue(signalsOnly.visibleSignalCount() > 0);
        assertTrue(signalsOnly.channels().stream()
                .flatMap(c -> c.messages().stream())
                .allMatch(FilteredMessage::showSignalChildren));
    }

    @Test
    void combinedFiltersUseAndSemantics() {
        Fixture fx = fixture();
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared()
                        .withChannelId(OptionalInt.of(1))
                        .withMatchFilter(MatchFilter.MATCHED)
                        .withCanTypeFilter(CanTypeFilter.STANDARD)
                        .withSearchText("speed")
        );
        assertEquals(1, result.visibleMessageCount());
        FilteredMessage msg = result.channels().getFirst().messages().getFirst();
        assertEquals(1, result.channels().getFirst().channel().channelId());
        assertTrue(msg.messageMatch().matched());
        assertFalse(msg.message().extended());
        assertTrue(msg.visibleSignals().stream().anyMatch(s -> s.name().toLowerCase().contains("speed")));
    }

    @Test
    void clearingFiltersRestoresFullHierarchy() {
        Fixture fx = fixture();
        long full = engine.filter(fx.inventory, fx.match, SourceInventoryFilterCriteria.cleared())
                .visibleMessageCount();
        SourceInventoryFilterResult filtered = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.MATCHED)
        );
        assertTrue(filtered.visibleMessageCount() < full);
        SourceInventoryFilterResult restored = engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared()
        );
        assertEquals(full, restored.visibleMessageCount());
    }

    @Test
    void sourceModelsRemainUnchangedAndNoReparse() {
        Fixture fx = fixture();
        int channelsBefore = fx.inventory.channels().size();
        long framesBefore = fx.inventory.totalCanFrames();
        DbcDatabase dbcBefore = fx.match.dbcDatabase().orElseThrow();
        engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared().withSearchText("DIAG_MUX_ON")
        );
        assertEquals(channelsBefore, fx.inventory.channels().size());
        assertEquals(framesBefore, fx.inventory.totalCanFrames());
        assertSame(dbcBefore, fx.match.dbcDatabase().orElseThrow());
        assertEquals(3, fx.match.summary().orElseThrow().dbcMessageCount());
    }

    @Test
    void dbcChangeRefreshesFilterableMetadata() {
        Fixture fx = fixture();
        DbcDatabase dbcB = database(
                message(0x201, false, "RenamedMsg", plain("NewSig", 0, 8))
        );
        SourceInventoryMatchState matchB = match(fx.inventory, dbcB, "b.dbc");
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                matchB,
                SourceInventoryFilterCriteria.cleared().withSearchText("NewSig")
        );
        assertEquals(1, result.visibleMessageCount());
        assertEquals("NewSig",
                result.channels().getFirst().messages().getFirst().visibleSignals().getFirst().name());
    }

    @Test
    void noDbcSelectedMatchFilterFallsBackToAll() {
        Fixture fx = fixture();
        SourceInventoryMatchState noDbc = SourceInventoryMatchState.noDbcSelected();
        assertEquals(CanDefinitionMatchStatus.NO_DBC_SELECTED, noDbc.status());
        SourceInventoryFilterResult result = engine.filter(
                fx.inventory,
                noDbc,
                SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.MATCHED)
        );
        assertEquals(
                engine.filter(fx.inventory, noDbc, SourceInventoryFilterCriteria.cleared())
                        .visibleMessageCount(),
                result.visibleMessageCount());
    }

    @Test
    void hierarchyLogicIsFormatIndependent() {
        for (MeasurementSourceFormat format : List.of(
                MeasurementSourceFormat.MF4,
                MeasurementSourceFormat.ASC,
                MeasurementSourceFormat.BLF
        )) {
            Fixture fx = fixture(format);
            SourceInventoryFilterResult result = engine.filter(
                    fx.inventory,
                    fx.match,
                    SourceInventoryFilterCriteria.cleared().withSearchText("VehicleSpeed")
            );
            assertEquals(1, result.visibleMessageCount(), format.name());
        }
    }

    @Test
    void existingMatchingCountsUnchangedByFiltering() {
        Fixture fx = fixture();
        CanDefinitionMatchSummary before = fx.match.summary().orElseThrow();
        engine.filter(
                fx.inventory,
                fx.match,
                SourceInventoryFilterCriteria.cleared()
                        .withMatchFilter(MatchFilter.UNMATCHED)
                        .withSearchText("0x")
        );
        CanDefinitionMatchSummary after = fx.match.summary().orElseThrow();
        assertEquals(before.globalMatchedUniqueCount(), after.globalMatchedUniqueCount());
        assertEquals(before.globalObservedUniqueCount(), after.globalObservedUniqueCount());
        assertEquals(before.overallMatchPercentage(), after.overallMatchPercentage());
    }

    private Fixture fixture() {
        return fixture(MeasurementSourceFormat.MF4);
    }

    private Fixture fixture(MeasurementSourceFormat format) {
        DbcDatabase dbc = database(
                message(0x201, false, "_201h_IS_DYN_VCU",
                        plain("VehicleSpeed", 0, 16),
                        plain("DIAG_MUX_ON", 16, 8)),
                message(0x208, false, "_208h_IS_DYN_CMM",
                        plain("Signal_X", 0, 8)),
                message(0x300, true, "ExtOnly", plain("ExtSig", 0, 8))
        );
        CanSourceInventory inventory = inventory(
                format,
                channel(1,
                        msg(1, 0x201, false, 10, false),
                        msg(1, 0x205, false, 3, false),
                        msg(1, 0x300, true, 2, false)),
                channel(2,
                        msg(2, 0x208, false, 5, false),
                        msg(2, 0x209, false, 4, true))
        );
        return new Fixture(inventory, match(inventory, dbc, "demo.dbc"), dbc);
    }

    private SourceInventoryMatchState match(
            CanSourceInventory inventory,
            DbcDatabase dbc,
            String name
    ) {
        return SourceInventoryMatchState.ofSummary(name, matchingService.match(inventory, dbc), dbc);
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
        long canFd = list.stream().filter(CanMessageInventory::canFdObserved).count();
        return new CanChannelInventory(
                channelId,
                "Channel " + channelId,
                frames,
                list.size(),
                standard,
                extended,
                canFd,
                list
        );
    }

    private static CanMessageInventory msg(
            int channel,
            long canId,
            boolean extended,
            long frames,
            boolean canFd
    ) {
        return new CanMessageInventory(new CanMessageKey(channel, canId, extended), frames, 8, 8, canFd);
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

    private record Fixture(CanSourceInventory inventory, SourceInventoryMatchState match, DbcDatabase dbc) {
    }
}
