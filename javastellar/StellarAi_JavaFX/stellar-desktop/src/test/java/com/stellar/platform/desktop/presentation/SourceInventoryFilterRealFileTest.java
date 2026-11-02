package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional local validation of smart filters against Bengaluru MF4 + 3.dbc. */
class SourceInventoryFilterRealFileTest {

    private static final Path[] MF4 = {
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Bengaluru_MF4\\"
                    + "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "Kalpesh2.0",
                    "Files_Prasad_Sangpal", "Bengaluru_MF4",
                    "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4")
    };
    private static final Path[] DBC = {
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\3.dbc"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "Kalpesh2.0",
                    "Files_Prasad_Sangpal", "Generic_DBC", "3.dbc")
    };

    static boolean present() {
        return resolve(MF4) != null && resolve(DBC) != null;
    }

    static Path resolve(Path[] candidates) {
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    @Test
    @EnabledIf("present")
    void smartFiltersAgainstKnownPair() throws Exception {
        Path mf4 = resolve(MF4);
        Path dbcPath = resolve(DBC);
        CanSourceInventory inventory = new SourceInventoryService(new ParserRegistry(List.of()), 1)
                .inventory(new Mf4FileReader(), mf4, ReaderContext.ofBatchSize(256), inv -> { });
        DbcDatabase dbc = new DbcParser().parse(dbcPath);
        SourceInventoryMatchState match = SourceInventoryMatchState.ofSummary(
                dbcPath.getFileName().toString(),
                new CanDefinitionMatchingService().match(inventory, dbc),
                dbc);
        SourceInventoryFilterEngine engine = new SourceInventoryFilterEngine();

        SourceInventoryFilterResult cleared = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared());
        CanDefinitionMatchSummary summary = match.summary().orElseThrow();
        assertEquals(124, summary.globalObservedUniqueCount());
        assertEquals(32, summary.globalMatchedUniqueCount());
        assertEquals(
                "Showing 124 IDs \u00B7 32 Matched",
                cleared.summaryText(false, summary.globalObservedUniqueCount(), summary.globalMatchedUniqueCount()));

        CanMessageMatch sampleMatched = summary.perChannelResults().stream()
                .flatMap(ch -> ch.messages().stream())
                .filter(CanMessageMatch::matched)
                .findFirst()
                .orElseThrow();
        String hex = String.format("0x%X", sampleMatched.canId());
        SourceInventoryFilterResult byId = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared().withSearchText(hex));
        assertTrue(byId.visibleMessageCount() >= 1);
        assertTrue(byId.channels().stream().flatMap(c -> c.messages().stream())
                .anyMatch(m -> m.message().canId() == sampleMatched.canId()));

        String msgName = sampleMatched.dbcMessageName().orElseThrow();
        SourceInventoryFilterResult byMsg = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared().withSearchText(msgName));
        assertTrue(byMsg.visibleMessageCount() >= 1);

        List<DbcSignal> signals = match.signalDefinitions(sampleMatched.canId(), sampleMatched.extended());
        assertTrue(!signals.isEmpty());
        String sigName = signals.getFirst().name();
        SourceInventoryFilterResult bySig = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared().withSearchText(sigName));
        assertTrue(bySig.channels().size() >= 1);
        assertTrue(bySig.channels().stream().flatMap(c -> c.messages().stream())
                .anyMatch(m -> m.visibleSignals().stream().anyMatch(s -> s.name().equals(sigName))));
        // Ancestry preserved: every visible message sits under a channel.
        assertTrue(bySig.channels().stream().noneMatch(c -> c.messages().isEmpty()));

        SourceInventoryFilterResult matchedOnly = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.MATCHED));
        assertEquals(matchedOnly.visibleMessageCount(), matchedOnly.visibleMatchedCount());

        SourceInventoryFilterResult unmatchedOnly = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared().withMatchFilter(MatchFilter.UNMATCHED));
        assertEquals(0, unmatchedOnly.visibleMatchedCount());

        int channelId = inventory.channels().getFirst().channelId();
        SourceInventoryFilterResult oneChannel = engine.filter(
                inventory, match,
                SourceInventoryFilterCriteria.cleared().withChannelId(OptionalInt.of(channelId)));
        assertEquals(1, oneChannel.channels().size());
        assertEquals(channelId, oneChannel.channels().getFirst().channel().channelId());

        SourceInventoryFilterResult restored = engine.filter(
                inventory, match, SourceInventoryFilterCriteria.cleared());
        assertEquals(cleared.visibleMessageCount(), restored.visibleMessageCount());
        assertEquals(inventory.totalCanFrames(), 1_051_734L);
    }
}
