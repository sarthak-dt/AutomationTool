package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional Bengaluru channel-selection processing validation. */
class ChannelSelectionRealFileTest {

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
    void selectedChannelsOnlyProduceSignals() throws Exception {
        Path mf4 = resolve(MF4);
        Path dbcPath = resolve(DBC);
        CanSourceInventory inventory = new SourceInventoryService(new ParserRegistry(List.of()), 1)
                .inventory(new Mf4FileReader(), mf4, ReaderContext.ofBatchSize(256), inv -> { });
        DbcDatabase dbc = new DbcParser().parse(dbcPath);
        CanDefinitionMatchSummary summary = new CanDefinitionMatchingService().match(inventory, dbc);

        assertEquals(3, inventory.totalCanChannels());
        assertEquals(124, summary.globalObservedUniqueCount());
        assertEquals(32, summary.globalMatchedUniqueCount());
        assertChannel(summary, 1, 69, 6);
        assertChannel(summary, 2, 56, 9);
        assertChannel(summary, 3, 31, 31);

        String identity = ProcessingSelection.sourceIdentity(mf4);
        MeasurementDecodePipeline pipeline = MeasurementDecodePipeline.createDefault();

        long ch1Frames = frameCount(inventory, 1);
        long ch3Frames = frameCount(inventory, 3);
        long expectedMultiSelected = ch1Frames + ch3Frames;

        InMemorySignalCatalogStore ch3Store = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(), dbcPath.getFileName().toString(), 50_000);
        List<CanonicalSignal> ch3Signals = new ArrayList<>();
        ProcessingResult ch3 = pipeline.process(
                mf4, dbc, ReaderContext.ofBatchSize(256),
                batch -> {
                    ch3Signals.addAll(batch);
                    ch3Store.accept(batch);
                },
                ProcessingProgressListener.NOOP,
                ProcessingSelection.forSource(identity, Set.of(3))
        );
        assertEquals(ch3Frames, ch3.statistics().framesSelectedForDecode());
        assertTrue(ch3.statistics().framesSkippedBySelection() > 0);
        assertFalse(ch3Signals.isEmpty());
        assertTrue(ch3Signals.stream().allMatch(s -> s.channel() == 3));
        SignalCatalog ch3Catalog = ch3Store.snapshot();
        assertTrue(ch3Catalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN)
                .allMatch(e -> e.channel() == 3));

        InMemorySignalCatalogStore multiStore = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(), dbcPath.getFileName().toString(), 50_000);
        List<CanonicalSignal> multi = new ArrayList<>();
        ProcessingResult both = pipeline.process(
                mf4, dbc, ReaderContext.ofBatchSize(256),
                batch -> {
                    multi.addAll(batch);
                    multiStore.accept(batch);
                },
                ProcessingProgressListener.NOOP,
                ProcessingSelection.forSource(identity, Set.of(1, 3))
        );
        assertEquals(expectedMultiSelected, both.statistics().framesSelectedForDecode());
        Set<Integer> channels = multi.stream().map(CanonicalSignal::channel).collect(Collectors.toSet());
        assertTrue(channels.contains(1));
        assertTrue(channels.contains(3));
        assertFalse(channels.contains(2));
        assertTrue(both.statistics().signalsEmitted() >= ch3.statistics().signalsEmitted());
        SignalCatalog multiCatalog = multiStore.snapshot();
        assertTrue(multiCatalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN)
                .noneMatch(e -> e.channel() == 2));
        assertTrue(multiCatalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN)
                .anyMatch(e -> e.channel() == 1));
        assertTrue(multiCatalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN)
                .anyMatch(e -> e.channel() == 3));
    }

    private static long frameCount(CanSourceInventory inventory, int channelId) {
        return inventory.channels().stream()
                .filter(ch -> ch.channelId() == channelId)
                .mapToLong(CanChannelInventory::frameCount)
                .findFirst()
                .orElseThrow();
    }

    private static void assertChannel(CanDefinitionMatchSummary summary, int id, long observed, long matched) {
        CanChannelMatchSummary ch = summary.perChannelResults().stream()
                .filter(c -> c.channelId() == id)
                .findFirst()
                .orElseThrow();
        assertEquals(observed, ch.observedCount());
        assertEquals(matched, ch.matchedCount());
    }
}
