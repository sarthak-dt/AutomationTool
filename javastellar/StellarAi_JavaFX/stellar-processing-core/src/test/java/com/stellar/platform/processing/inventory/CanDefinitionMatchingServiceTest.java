package com.stellar.platform.processing.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnosticStatus;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanDefinitionMatchingServiceTest {

    private final CanDefinitionMatchingService service = new CanDefinitionMatchingService();

    @Test
    void zeroMatch() {
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, false, 0x10, 0x20, 0x30)
        );
        DbcDatabase dbc = dbc(false, 0x111, 0x222);

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS, summary.matchingStatus());
        assertEquals(2, summary.dbcMessageCount());
        assertEquals(3, summary.globalObservedUniqueCount());
        assertEquals(0, summary.globalMatchedUniqueCount());
        assertEquals(3, summary.globalUnmatchedUniqueCount());
        assertEquals(0.0, summary.overallMatchPercentage(), 1e-9);
        assertEquals(0, summary.perChannelResults().getFirst().matchedCount());
        assertEquals(3, summary.perChannelResults().getFirst().unmatchedCount());
    }

    @Test
    void partialMatch() {
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, false, 0x10, 0x20, 0x30)
        );
        DbcDatabase dbc = dbc(false, 0x20, 0x99);

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(CanDefinitionMatchStatus.READY, summary.matchingStatus());
        assertEquals(1, summary.globalMatchedUniqueCount());
        assertEquals(2, summary.globalUnmatchedUniqueCount());
        assertEquals(100.0 / 3.0, summary.overallMatchPercentage(), 1e-9);
        CanChannelMatchSummary ch = summary.perChannelResults().getFirst();
        assertEquals(1, ch.matchedCount());
        assertEquals(2, ch.unmatchedCount());
        assertEquals(100.0 / 3.0, ch.matchPercentage(), 1e-9);
        assertEquals("M_20", ch.matchedMessages().getFirst().dbcMessageName().orElseThrow());
    }

    @Test
    void completeMatch() {
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, false, 0x10, 0x20)
        );
        DbcDatabase dbc = dbc(false, 0x10, 0x20, 0x30);

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(CanDefinitionMatchStatus.READY, summary.matchingStatus());
        assertEquals(3, summary.dbcMessageCount());
        assertEquals(2, summary.globalObservedUniqueCount());
        assertEquals(2, summary.globalMatchedUniqueCount());
        assertEquals(0, summary.globalUnmatchedUniqueCount());
        assertEquals(100.0, summary.overallMatchPercentage(), 1e-9);
        assertEquals(100.0, summary.perChannelResults().getFirst().matchPercentage(), 1e-9);
    }

    @Test
    void multipleChannelsAndSameIdCrossChannelGlobalDedup() {
        // Channel A: 1,2,3  Channel B: 3,4,5  DBC: 2,3,4
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, false, 1, 2, 3),
                channel(2, false, 3, 4, 5)
        );
        DbcDatabase dbc = dbc(false, 2, 3, 4);

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        CanChannelMatchSummary a = summary.perChannelResults().stream()
                .filter(c -> c.channelId() == 1).findFirst().orElseThrow();
        CanChannelMatchSummary b = summary.perChannelResults().stream()
                .filter(c -> c.channelId() == 2).findFirst().orElseThrow();

        assertEquals(2, a.matchedCount());
        assertEquals(2, b.matchedCount());
        assertEquals(5, summary.globalObservedUniqueCount());
        assertEquals(3, summary.globalMatchedUniqueCount());
        assertEquals(2, summary.globalUnmatchedUniqueCount());
        assertEquals(60.0, summary.overallMatchPercentage(), 1e-9);
        // Critical: global matched is UNION (3), not sum of channel matches (4)
        assertEquals(4, a.matchedCount() + b.matchedCount());
    }

    @Test
    void standardVsExtendedAreDistinctIdentities() {
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                mixedChannel(1,
                        msg(1, 0x123, false, 5),
                        msg(1, 0x123, true, 7))
        );
        DbcDatabase dbc = new DbcDatabase(
                "",
                List.of(),
                List.of(
                        message(CanIdKey.fromFrame(0x123, false), "Std"),
                        message(CanIdKey.fromFrame(0x456, true), "ExtOther")
                ),
                List.of()
        );

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(2, summary.globalObservedUniqueCount());
        assertEquals(1, summary.globalMatchedUniqueCount());
        CanChannelMatchSummary ch = summary.perChannelResults().getFirst();
        assertEquals(1, ch.matchedCount());
        assertEquals(1, ch.unmatchedCount());
        assertTrue(ch.matchedMessages().getFirst().extended() == false);
        assertTrue(ch.unmatchedMessages().getFirst().extended());
    }

    @Test
    void extendedIdIsNotTruncatedToElevenBits() {
        long extendedId = 0x18FA_F110L;
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.MF4,
                channel(1, true, extendedId)
        );
        DbcDatabase dbc = new DbcDatabase(
                "",
                List.of(),
                List.of(message(CanIdKey.fromFrame(extendedId, true), "J1939")),
                List.of()
        );

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(1, summary.globalMatchedUniqueCount());
        assertEquals(extendedId, summary.perChannelResults().getFirst().matchedMessages().getFirst().canId());
    }

    @Test
    void zeroObservedIds() {
        CanSourceInventory inventory = CanSourceInventory.empty(
                Path.of("empty.asc"),
                MeasurementSourceFormat.ASC,
                CanInventoryStatus.INVENTORY_READY
        );
        DbcDatabase dbc = dbc(false, 0x10, 0x20);

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(CanDefinitionMatchStatus.READY, summary.matchingStatus());
        assertEquals(2, summary.dbcMessageCount());
        assertEquals(0, summary.globalObservedUniqueCount());
        assertEquals(0, summary.globalMatchedUniqueCount());
        assertEquals(0.0, summary.overallMatchPercentage(), 1e-9);
        assertTrue(summary.perChannelResults().isEmpty());
    }

    @Test
    void emptyDbcYieldsNoDbcSelected() {
        CanSourceInventory inventory = inventory(
                MeasurementSourceFormat.ASC,
                channel(1, false, 0x10)
        );
        DbcDatabase dbc = new DbcDatabase("", List.of(), List.of(), List.of());

        CanDefinitionMatchSummary summary = service.match(inventory, dbc);

        assertEquals(CanDefinitionMatchStatus.NO_DBC_SELECTED, summary.matchingStatus());
        assertEquals(0, summary.dbcMessageCount());
        assertEquals(0, summary.globalMatchedUniqueCount());
    }

    @Test
    void matchingIsIndependentOfSourceFormat() {
        long[] ids = {0x100, 0x200, 0x300};
        DbcDatabase dbc = dbc(false, 0x100, 0x300);

        CanDefinitionMatchSummary mf4 = service.match(
                inventory(MeasurementSourceFormat.MF4, channel(1, false, ids)),
                dbc
        );
        CanDefinitionMatchSummary asc = service.match(
                inventory(MeasurementSourceFormat.ASC, channel(1, false, ids)),
                dbc
        );
        CanDefinitionMatchSummary blf = service.match(
                inventory(MeasurementSourceFormat.BLF, channel(1, false, ids)),
                dbc
        );

        assertEquals(mf4.globalMatchedUniqueCount(), asc.globalMatchedUniqueCount());
        assertEquals(mf4.globalMatchedUniqueCount(), blf.globalMatchedUniqueCount());
        assertEquals(mf4.overallMatchPercentage(), asc.overallMatchPercentage(), 1e-9);
        assertEquals(mf4.overallMatchPercentage(), blf.overallMatchPercentage(), 1e-9);
        assertEquals(2, mf4.globalMatchedUniqueCount());
        assertEquals(3, mf4.globalObservedUniqueCount());
    }

    @Test
    void sourceInventoryIsNotModifiedByMatching() {
        CanChannelInventory channel = channel(1, false, 0x10, 0x20);
        CanSourceInventory inventory = inventory(MeasurementSourceFormat.ASC, channel);
        int channelCountBefore = inventory.channels().size();
        long uniqueBefore = inventory.totalUniqueMessageIds();
        long framesBefore = inventory.totalCanFrames();
        int messagesBefore = inventory.channels().getFirst().messages().size();
        List<CanMessageInventory> messageView = inventory.channels().getFirst().messages();

        service.match(inventory, dbc(false, 0x10));

        assertEquals(channelCountBefore, inventory.channels().size());
        assertEquals(uniqueBefore, inventory.totalUniqueMessageIds());
        assertEquals(framesBefore, inventory.totalCanFrames());
        assertEquals(messagesBefore, inventory.channels().getFirst().messages().size());
        assertSame(messageView, inventory.channels().getFirst().messages());
        assertEquals(CanInventoryStatus.INVENTORY_READY, inventory.status());
    }

    @Test
    void percentageHandlesZeroSafely() {
        assertEquals(0.0, CanDefinitionMatchingService.percentage(0, 0), 1e-9);
        assertEquals(0.0, CanDefinitionMatchingService.percentage(5, 0), 1e-9);
        assertEquals(50.0, CanDefinitionMatchingService.percentage(1, 2), 1e-9);
    }

    @Test
    void obdStylePartialMatchIsOneOfThree() {
        CanChannelInventory channel = channel(1, false, 0x7E8, 0x7E0, 0x7DF);
        DbcDatabase dbc = dbc(false, 0x7E8);

        CanChannelMatchSummary summary = service.matchChannel(channel, dbc);
        List<CanIdMatchDiagnostic> diags = service.diagnoseChannel(channel, dbc);

        assertEquals(1L, summary.matchedCount());
        assertEquals(3L, summary.observedCount());
        assertEquals(100.0 / 3.0, summary.matchPercentage(), 1e-9);
        assertEquals(3, diags.size());
        assertEquals(CanIdMatchDiagnosticStatus.MATCHED,
                diags.stream().filter(d -> d.observedCanId() == 0x7E8L).findFirst().orElseThrow().status());
        assertEquals(CanIdMatchDiagnosticStatus.UNMATCHED,
                diags.stream().filter(d -> d.observedCanId() == 0x7E0L).findFirst().orElseThrow().status());
        assertEquals(CanIdMatchDiagnosticStatus.UNMATCHED,
                diags.stream().filter(d -> d.observedCanId() == 0x7DFL).findFirst().orElseThrow().status());
    }

    @Test
    void extendedDbcRawFlagNormalizesToSameCanonicalIdentity() {
        long extendedId = 0x18FA_F110L;
        CanChannelInventory channel = channel(1, true, extendedId);
        DbcDatabase dbc = new DbcDatabase(
                "",
                List.of(),
                List.of(message(CanIdKey.fromDbcRawId(CanIdKey.DBC_EXTENDED_FLAG | extendedId), "J1939")),
                List.of()
        );

        CanChannelMatchSummary summary = service.matchChannel(channel, dbc);
        assertEquals(1L, summary.matchedCount());
        assertEquals(CanIdMatchDiagnosticStatus.MATCHED,
                service.diagnoseChannel(channel, dbc).getFirst().status());
    }

    @Test
    void standardVsExtendedSameNumericIsTypeMismatchNotMatch() {
        CanChannelInventory channel = channel(1, false, 0x123);
        DbcDatabase dbc = new DbcDatabase(
                "",
                List.of(),
                List.of(message(CanIdKey.fromFrame(0x123, true), "Ext123")),
                List.of()
        );

        CanChannelMatchSummary summary = service.matchChannel(channel, dbc);
        List<CanIdMatchDiagnostic> diags = service.diagnoseChannel(channel, dbc);

        assertEquals(0L, summary.matchedCount());
        assertEquals(1, diags.size());
        assertEquals(CanIdMatchDiagnosticStatus.TYPE_MISMATCH, diags.getFirst().status());
    }

    private static CanSourceInventory inventory(
            MeasurementSourceFormat format,
            CanChannelInventory... channels
    ) {
        List<CanChannelInventory> list = List.of(channels);
        long frames = list.stream().mapToLong(CanChannelInventory::frameCount).sum();
        long uniqueSum = list.stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        return new CanSourceInventory(
                Path.of("synthetic." + format.name().toLowerCase()),
                "synthetic." + format.name().toLowerCase(),
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

    private static CanChannelInventory channel(int channelId, boolean extended, long... canIds) {
        List<CanMessageInventory> messages = new ArrayList<>();
        long frames = 0;
        long standard = 0;
        long extendedCount = 0;
        for (long id : canIds) {
            long count = 1 + (id % 3);
            messages.add(msg(channelId, id, extended, count));
            frames += count;
            if (extended) {
                extendedCount++;
            } else {
                standard++;
            }
        }
        return new CanChannelInventory(
                channelId,
                "Channel " + channelId,
                frames,
                messages.size(),
                standard,
                extendedCount,
                0,
                messages
        );
    }

    private static CanChannelInventory mixedChannel(int channelId, CanMessageInventory... messages) {
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

    private static CanMessageInventory msg(int channel, long canId, boolean extended, long frameCount) {
        return new CanMessageInventory(
                new CanMessageKey(channel, canId, extended),
                frameCount,
                8,
                8,
                false
        );
    }

    private static DbcDatabase dbc(boolean extended, long... ids) {
        List<DbcMessage> messages = new ArrayList<>();
        for (long id : ids) {
            messages.add(message(CanIdKey.fromFrame(id, extended), "M_" + Long.toHexString(id)));
        }
        return new DbcDatabase("", List.of(), messages, List.of());
    }

    private static DbcMessage message(CanIdKey key, String name) {
        return new DbcMessage(key, name, 8, "", List.of(), "");
    }
}
