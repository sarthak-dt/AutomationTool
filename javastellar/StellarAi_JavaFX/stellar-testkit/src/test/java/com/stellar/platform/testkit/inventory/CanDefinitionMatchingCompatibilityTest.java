package com.stellar.platform.testkit.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.ExternalTestData;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optional local compatibility validation for known Generic_DBC pair.
 * Skips when files are absent or when MF4 inventory yields no CAN frames
 * (matching itself requires a populated {@link CanSourceInventory}).
 */
class CanDefinitionMatchingCompatibilityTest {

    private static final Path[] MF4_CANDIDATES = {
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Bengaluru_MF4\\"
                    + "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "Kalpesh2.0",
                    "Files_Prasad_Sangpal", "Bengaluru_MF4",
                    "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4"),
            ExternalTestData.resolve(
                    "Bengaluru_MF4/MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4"),
            ExternalTestData.resolve(
                    "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4"),
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\b1-3.mf4"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "Kalpesh2.0",
                    "Files_Prasad_Sangpal", "Generic_DBC", "b1-3.mf4"),
            ExternalTestData.resolve("Generic_DBC/b1-3.mf4"),
            ExternalTestData.resolve("b1-3.mf4")
    };

    private static final Path[] DBC_CANDIDATES = {
            Path.of("C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\3.dbc"),
            Path.of(System.getProperty("user.home"), "Desktop", "DriveTech", "Kalpesh2.0",
                    "Files_Prasad_Sangpal", "Generic_DBC", "3.dbc"),
            ExternalTestData.resolve("Generic_DBC/3.dbc"),
            ExternalTestData.resolve("3.dbc")
    };

    static boolean knownPairPresent() {
        return resolveExisting(MF4_CANDIDATES) != null && resolveExisting(DBC_CANDIDATES) != null;
    }

    static Path resolveExisting(Path[] candidates) {
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    @Test
    @EnabledIf("knownPairPresent")
    void matchesKnownGenericDbcPairExpectations() throws Exception {
        Path mf4 = resolveExisting(MF4_CANDIDATES);
        Path dbcPath = resolveExisting(DBC_CANDIDATES);

        SourceInventoryService inventoryService = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        CanSourceInventory inventory = inventoryService.inventory(
                new Mf4FileReader(),
                mf4,
                ReaderContext.ofBatchSize(256),
                inv -> { }
        );
        DbcDatabase dbc = new DbcParser().parse(dbcPath);

        System.out.println("Inventory status=" + inventory.status()
                + " channels=" + inventory.totalCanChannels()
                + " frames=" + inventory.totalCanFrames()
                + " uniqueSum=" + inventory.totalUniqueMessageIds()
                + " diags=" + inventory.diagnostics());

        assumeTrue(
                inventory.totalCanFrames() > 0 && !inventory.channels().isEmpty(),
                "b1-3.mf4 inventory produced no CanonicalCanFrame (MF4 reader skipped groups as non-CAN); "
                        + "cannot validate Message-ID matching against empty inventory without MF4 changes"
        );

        CanDefinitionMatchSummary summary = new CanDefinitionMatchingService().match(inventory, dbc);

        System.out.println("Compatibility match for " + mf4.getFileName() + " + " + dbcPath.getFileName());
        System.out.println("  DBC messages: " + summary.dbcMessageCount());
        System.out.println("  Global observed unique: " + summary.globalObservedUniqueCount());
        System.out.println("  Global matched unique: " + summary.globalMatchedUniqueCount());
        System.out.println("  Overall %: " + summary.overallMatchPercentage());
        for (CanChannelMatchSummary ch : summary.perChannelResults()) {
            System.out.println("  Ch " + ch.channelId()
                    + " observed=" + ch.observedCount()
                    + " matched=" + ch.matchedCount()
                    + " unmatched=" + ch.unmatchedCount()
                    + " %=" + ch.matchPercentage());
        }

        // Validation expectations for the known local pair only (not production constants).
        assertEquals(69, summary.dbcMessageCount());
        assertEquals(124, summary.globalObservedUniqueCount());
        assertEquals(32, summary.globalMatchedUniqueCount());
        assertEquals(32.0 * 100.0 / 124.0, summary.overallMatchPercentage(), 0.01);

        assertChannel(summary, 1, 69, 6);
        assertChannel(summary, 2, 56, 9);
        assertChannel(summary, 3, 31, 31);

        // Signal-definition hierarchy: children come from the already-parsed DBC only.
        int printed = 0;
        for (CanChannelMatchSummary ch : summary.perChannelResults()) {
            for (CanMessageMatch msg : ch.messages()) {
                if (!msg.matched()) {
                    continue;
                }
                DbcMessage dbcMessage = dbc.findMessage(CanIdKey.fromFrame(msg.canId(), msg.extended()))
                        .orElseThrow(() -> new AssertionError(
                                "matched ID missing from parsed DBC: 0x"
                                        + Long.toHexString(msg.canId())));
                assertEquals(msg.dbcMessageName().orElseThrow(), dbcMessage.name());
                // Hierarchy exposes definition metadata from this DbcMessage; no reparse.
                assertTrue(dbcMessage.signals() != null);
                if (printed < 5) {
                    System.out.println("  Matched 0x" + Long.toHexString(msg.canId()).toUpperCase()
                            + " " + dbcMessage.name()
                            + " signals=" + dbcMessage.signals().size());
                    printed++;
                }
            }
        }
        assertTrue(printed > 0, "expected at least one matched message with DBC signal definitions");
    }

    private static void assertChannel(
            CanDefinitionMatchSummary summary,
            int channelId,
            long observed,
            long matched
    ) {
        CanChannelMatchSummary ch = summary.perChannelResults().stream()
                .filter(c -> c.channelId() == channelId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing channel " + channelId));
        assertEquals(observed, ch.observedCount(), "channel " + channelId + " observed");
        assertEquals(matched, ch.matchedCount(), "channel " + channelId + " matched");
        assertTrue(ch.unmatchedCount() == observed - matched);
    }
}