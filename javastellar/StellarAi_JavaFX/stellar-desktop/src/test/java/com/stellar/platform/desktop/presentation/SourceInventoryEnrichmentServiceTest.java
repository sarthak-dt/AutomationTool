package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingCoordinator.ParsedDefinition;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryEnrichmentServiceTest {

    @Test
    void syntheticMatchUnmatchAndSignalCounts() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(
                message(0xA, false, "MessageA", plain("S1", 0, 8), plain("S2", 8, 8)),
                message(0xC, false, "MessageC", plain("S3", 0, 8))
        );
        Map<DefinitionSourceId, ParsedDefinition> defs = Map.of(
                defId, new ParsedDefinition(defId, "demo.dbc", dbc));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry mapping = confirmed(key, defId, "demo.dbc", 3, 2);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key, mapping, id -> Optional.ofNullable(defs.get(id)));

        CanMessageDefinitionMatch a = SourceInventoryEnrichmentService.enrich(source, key, 0xA, false, 10, ctx);
        CanMessageDefinitionMatch b = SourceInventoryEnrichmentService.enrich(source, key, 0xB, false, 10, ctx);
        CanMessageDefinitionMatch c = SourceInventoryEnrichmentService.enrich(source, key, 0xC, false, 10, ctx);

        assertEquals(InventoryEnrichmentMatchState.MATCHED, a.matchState());
        assertEquals("MessageA", a.dbcMessageName().orElseThrow());
        assertEquals(2, a.signalCount());
        assertEquals(InventoryEnrichmentMatchState.UNMATCHED, b.matchState());
        assertEquals(0, b.signalCount());
        assertEquals(InventoryEnrichmentMatchState.MATCHED, c.matchState());
        assertEquals("MessageC", c.dbcMessageName().orElseThrow());
        assertEquals(1, c.signalCount());
    }

    @Test
    void standardExtendedIdentityRespected() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(message(0x123, false, "StdOnly", plain("X", 0, 8)));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key,
                confirmed(key, defId, "std.dbc", 2, 1),
                id -> Optional.of(new ParsedDefinition(defId, "std.dbc", dbc)));
        assertEquals(InventoryEnrichmentMatchState.MATCHED,
                SourceInventoryEnrichmentService.enrich(source, key, 0x123, false, 1, ctx).matchState());
        assertEquals(InventoryEnrichmentMatchState.UNMATCHED,
                SourceInventoryEnrichmentService.enrich(source, key, 0x123, true, 1, ctx).matchState());
    }

    @Test
    void multiplexingMetadataPreservedInDrillDown() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcSignal mux = new DbcSignal(
                "MODE", 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 255, "", List.of(), DbcMultiplexRole.MULTIPLEXOR, null, "", DbcValueTable.empty());
        DbcSignal leaf = new DbcSignal(
                "VehicleSpeed", 8, 16, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                0.1, 0, 0, 250, "km/h", List.of("GW"), DbcMultiplexRole.MULTIPLEXED, 1, "", DbcValueTable.empty());
        DbcDatabase dbc = database(message(0x201, false, "MuxMsg", mux, leaf));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key,
                confirmed(key, defId, "mux.dbc", 1, 1),
                id -> Optional.of(new ParsedDefinition(defId, "mux.dbc", dbc)));
        CanMessageDefinitionMatch match = SourceInventoryEnrichmentService.enrich(source, key, 0x201, false, 5, ctx);
        assertEquals(2, match.signalCount());
        assertEquals("Multiplexer", match.signalDefinitions().get(0).multiplexLabel());
        assertEquals("Mux 1", match.signalDefinitions().get(1).multiplexLabel());
        assertEquals("km/h", match.signalDefinitions().get(1).unitDisplay());
        assertEquals("Intel / Little Endian", match.signalDefinitions().get(1).byteOrderLabel());
    }

    @Test
    void noDbcKeepsRawInventory() {
        ProjectSourceId source = ProjectSourceId.generate();
        CanSourceInventory inv = inventory(source, channel(1,
                msg(1, 0x10, false, 3), msg(1, 0x20, false, 4)));
        var result = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "", CanTypeFilter.ALL,
                MatchFilter.ALL, ContentFilter.ALL, key -> SourceChannelEnrichmentContext.noDbc(key));
        assertEquals(2, result.rows().size());
        assertTrue(result.rows().stream().allMatch(r -> r.matchState() == InventoryEnrichmentMatchState.NO_DBC));
        assertTrue(result.rows().stream().allMatch(r -> r.dbcMessageDisplay().equals("\u2014")));
    }

    @Test
    void recommendedContextIsUnconfirmed() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(message(0x1, false, "M", plain("S", 0, 8)));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry mapping = new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "rec.dbc", null, "", 1, 1, 1.0);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key, mapping, id -> Optional.of(new ParsedDefinition(defId, "rec.dbc", dbc)));
        assertTrue(ctx.recommendedUnconfirmed());
        assertTrue(ctx.hasDbc());
        assertEquals(InventoryEnrichmentMatchState.MATCHED,
                SourceInventoryEnrichmentService.enrich(source, key, 0x1, false, 1, ctx).matchState());
    }

    @Test
    void confirmationDoesNotRequireReindex() {
        AtomicInteger inventoryLookups = new AtomicInteger();
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(message(0x1, false, "M", plain("S", 0, 8)));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelMappingEntry recommended = new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "x.dbc", null, "", 1, 1, 1.0);
        SourceChannelMappingEntry confirmed = new SourceChannelMappingEntry(
                key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "x.dbc", defId, "x.dbc", 1, 1, 1.0);
        var lookup = (java.util.function.Function<DefinitionSourceId, Optional<ParsedDefinition>>) id -> {
            inventoryLookups.incrementAndGet();
            return Optional.of(new ParsedDefinition(defId, "x.dbc", dbc));
        };
        SourceChannelEnrichmentContext before = SourceInventoryEnrichmentService.contextFor(key, recommended, lookup);
        SourceChannelEnrichmentContext after = SourceInventoryEnrichmentService.contextFor(key, confirmed, lookup);
        assertTrue(before.recommendedUnconfirmed());
        assertFalse(after.recommendedUnconfirmed());
        assertEquals(before.matchedUniqueMessageCount(), after.matchedUniqueMessageCount());
        // definition lookup only — no measurement inventory access in this test path
        assertTrue(inventoryLookups.get() >= 2);
    }

    @Test
    void searchByMessageAndSignalNames() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(
                message(0x201, false, "BCM_502", plain("VehicleSpeed", 0, 16), plain("Other", 16, 8)),
                message(0x208, false, "OtherMsg", plain("Temp", 0, 8))
        );
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key,
                confirmed(key, defId, "demo.dbc", 2, 2),
                id -> Optional.of(new ParsedDefinition(defId, "demo.dbc", dbc)));
        CanSourceInventory inv = inventory(source, channel(1,
                msg(1, 0x201, false, 10), msg(1, 0x208, false, 5)));
        var byMsg = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "BCM_502", CanTypeFilter.ALL,
                MatchFilter.ALL, ContentFilter.ALL, k -> ctx);
        assertEquals(1, byMsg.rows().size());
        assertEquals(0x201, byMsg.rows().getFirst().canId());
        var bySig = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "VehicleSpeed", CanTypeFilter.ALL,
                MatchFilter.ALL, ContentFilter.ALL, k -> ctx);
        assertEquals(1, bySig.rows().size());
        assertEquals(0x201, bySig.rows().getFirst().canId());
        var byId = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "0x208", CanTypeFilter.ALL,
                MatchFilter.ALL, ContentFilter.ALL, k -> ctx);
        assertEquals(1, byId.rows().size());
        assertEquals(0x208, byId.rows().getFirst().canId());
    }

    @Test
    void matchFilterComposes() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defId = DefinitionSourceId.generate();
        DbcDatabase dbc = database(message(0xA, false, "A", plain("S", 0, 8)));
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        SourceChannelEnrichmentContext ctx = SourceInventoryEnrichmentService.contextFor(
                key,
                confirmed(key, defId, "d.dbc", 2, 1),
                id -> Optional.of(new ParsedDefinition(defId, "d.dbc", dbc)));
        CanSourceInventory inv = inventory(source, channel(1,
                msg(1, 0xA, false, 1), msg(1, 0xB, false, 1)));
        assertEquals(1, SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "", CanTypeFilter.ALL,
                MatchFilter.MATCHED, ContentFilter.ALL, k -> ctx).rows().size());
        assertEquals(1, SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.of(1), "", CanTypeFilter.ALL,
                MatchFilter.UNMATCHED, ContentFilter.ALL, k -> ctx).rows().size());
    }

    @Test
    void sameCanIdDifferentChannelsIndependent() {
        ProjectSourceId source = ProjectSourceId.generate();
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        DbcDatabase dbcA = database(message(0x123, false, "OnlyA", plain("S", 0, 8)));
        DbcDatabase dbcB = database(); // empty
        CanSourceInventory inv = inventory(source,
                channel(1, msg(1, 0x123, false, 1)),
                channel(3, msg(3, 0x123, false, 1)));
        var result = SourceInventoryWorkspaceQuery.queryMessages(
                source, inv, OptionalInt.empty(), "", CanTypeFilter.ALL,
                MatchFilter.ALL, ContentFilter.ALL,
                key -> {
                    if (key.channelId() == 1) {
                        return SourceInventoryEnrichmentService.contextFor(
                                key, confirmed(key, defA, "a.dbc", 1, 1),
                                id -> Optional.of(new ParsedDefinition(defA, "a.dbc", dbcA)));
                    }
                    return SourceInventoryEnrichmentService.contextFor(
                            key, confirmed(key, defB, "b.dbc", 1, 0),
                            id -> Optional.of(new ParsedDefinition(defB, "b.dbc", dbcB)));
                });
        assertEquals(2, result.rows().size());
        CanMessageInventoryRow ch1 = result.rows().stream().filter(r -> r.channelId() == 1).findFirst().orElseThrow();
        CanMessageInventoryRow ch3 = result.rows().stream().filter(r -> r.channelId() == 3).findFirst().orElseThrow();
        assertEquals(InventoryEnrichmentMatchState.MATCHED, ch1.matchState());
        assertEquals(InventoryEnrichmentMatchState.UNMATCHED, ch3.matchState());
    }

    private static SourceChannelMappingEntry confirmed(
            SourceChannelKey key, DefinitionSourceId def, String name, long observed, long matched) {
        double cov = observed == 0 ? 0 : (double) matched / (double) observed;
        return new SourceChannelMappingEntry(
                key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                def, name, def, name, observed, matched, cov);
    }

    private static DbcDatabase database(DbcMessage... messages) {
        return new DbcDatabase("", List.of(), List.of(messages), List.of());
    }

    private static DbcMessage message(long id, boolean ext, String name, DbcSignal... signals) {
        return new DbcMessage(CanIdKey.fromFrame(id, ext), name, 8, "TX", List.of(signals), "");
    }

    private static DbcSignal plain(String name, int start, int len) {
        return new DbcSignal(
                name, start, len, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                1, 0, 0, 255, "", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty());
    }

    private static CanSourceInventory inventory(ProjectSourceId source, CanChannelInventory... channels) {
        long frames = 0;
        long unique = 0;
        for (CanChannelInventory ch : channels) {
            frames += ch.frameCount();
            unique += ch.uniqueMessageCount();
        }
        // Use domain union helper
        unique = CanSourceInventory.countGlobalUniqueCanIdentities(List.of(channels));
        return new CanSourceInventory(
                Path.of(source.value() + ".mf4"), source.value() + ".mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, channels.length, frames, unique, true,
                List.of(channels), List.of());
    }

    private static CanChannelInventory channel(int id, CanMessageInventory... messages) {
        long frames = 0;
        for (CanMessageInventory m : messages) {
            frames += m.frameCount();
        }
        return new CanChannelInventory(id, "Channel " + id, frames, messages.length, messages.length, 0, 0, List.of(messages));
    }

    private static CanMessageInventory msg(int channel, long canId, boolean ext, long frames) {
        return new CanMessageInventory(new CanMessageKey(channel, canId, ext), frames, 8, 8, false);
    }
}
