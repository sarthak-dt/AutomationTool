package com.stellar.platform.testkit;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.map.GpsLocationSignalIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BLF → CanonicalCanFrame → existing DBC decoder → catalog. No BLF-specific decode path.
 */
class BlfProductionPipelineTest {

    private static DbcDatabase database;
    private static MeasurementDecodePipeline pipeline;
    private static AutomotiveFileReader reader;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void load() throws Exception {
        Path dbc = Path.of(BlfProductionPipelineTest.class.getClassLoader()
                .getResource("dbc/pipeline.dbc").toURI());
        database = new DbcParser().parse(dbc);
        pipeline = new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder());
        reader = ParserRegistry.load().findReader(Path.of("probe.blf")).orElseThrow(
                () -> new IllegalStateException("BLF AutomotiveFileReader not on ServiceLoader classpath"));
    }

    @Test
    void goldenBlfPlusDbcExactPhysicalValues() throws Exception {
        Path blf = BlfBinaryFixtures.classic(
                tempDir.resolve("golden.blf"), 0, 0x100, BlfBinaryFixtures.enginePayload(2500.0, 50, 1),
                1_000_000_000L);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(blf, ReaderContext.ofBatchSize(8), frames::addAll, w -> { });
        assertEquals(1, frames.size());
        assertEquals(0x100L, frames.getFirst().canId());
        assertArrayEqualsPayload(BlfBinaryFixtures.enginePayload(2500.0, 50, 1), frames.getFirst().data());

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(reader, blf, database, ReaderContext.ofBatchSize(8), signals::addAll);
        assertEquals(1, result.statistics().sourceFramesRead());
        assertEquals(2500.0, find(signals, "EngineSpeed").physicalValue(), 1e-9);
        assertEquals(50.0, find(signals, "EngineTemp").physicalValue(), 1e-9);
        assertEquals(1.0, find(signals, "StatusFlags").physicalValue(), 1e-9);
        assertEquals("rpm", find(signals, "EngineSpeed").unit());
        assertEquals(1_000_000_000L, find(signals, "EngineSpeed").timestampNanos());
        assertEquals(0, find(signals, "EngineSpeed").channel());
    }

    @Test
    void multiplexInactiveBranchIsNotFabricated() throws Exception {
        Path blf = BlfBinaryFixtures.twoClassic(
                tempDir.resolve("mux.blf"),
                1, 0x600, new byte[]{0, 40}, 10_000_000L,
                1, 0x600, new byte[]{1, (byte) 0xC8}, 20_000_000L);
        List<CanonicalSignal> signals = new ArrayList<>();
        pipeline.process(reader, blf, database, ReaderContext.ofBatchSize(8), signals::addAll);
        List<CanonicalSignal> speeds = signals.stream().filter(s -> "Speed".equals(s.signalName())).toList();
        List<CanonicalSignal> temps = signals.stream().filter(s -> "Temperature".equals(s.signalName())).toList();
        assertEquals(1, speeds.size());
        assertEquals(1, temps.size());
        assertEquals(40.0, speeds.getFirst().physicalValue(), 1e-9);
        assertEquals(-56.0, temps.getFirst().physicalValue(), 1e-9);
    }

    @Test
    void twoBlfFilesKeepIndependentProvenance() throws Exception {
        Path a = BlfBinaryFixtures.classic(
                tempDir.resolve("a.blf"), 1, 0x100, BlfBinaryFixtures.enginePayload(2500.0, 50, 1), 1_000L);
        Path b = BlfBinaryFixtures.classic(
                tempDir.resolve("b.blf"), 2, 0x100, BlfBinaryFixtures.enginePayload(1000.0, 60, 2), 2_000L);
        List<CanonicalSignal> signalsA = new ArrayList<>();
        List<CanonicalSignal> signalsB = new ArrayList<>();
        pipeline.process(reader, a, database, ReaderContext.ofBatchSize(8), signalsA::addAll);
        pipeline.process(reader, b, database, ReaderContext.ofBatchSize(8), signalsB::addAll);
        assertEquals(1, find(signalsA, "EngineSpeed").channel());
        assertEquals(2, find(signalsB, "EngineSpeed").channel());
        assertEquals(2500.0, find(signalsA, "EngineSpeed").physicalValue(), 1e-9);
        assertEquals(1000.0, find(signalsB, "EngineSpeed").physicalValue(), 1e-9);
        assertNotEquals(find(signalsA, "EngineSpeed").timestampNanos(), find(signalsB, "EngineSpeed").timestampNanos());
    }

    @Test
    void blfWithoutDbcStillParsesFrames() throws Exception {
        Path blf = BlfBinaryFixtures.classic(
                tempDir.resolve("nodbc.blf"), 1, 0x7E8, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 5_000L);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(blf, ReaderContext.ofBatchSize(4), frames::addAll, w -> { });
        assertEquals(1, frames.size());
        assertEquals(0x7E8L, frames.getFirst().canId());
        assertEquals(8, frames.getFirst().dlc());
    }

    @Test
    void serviceLoaderPipelineDiscoversBlfReader() throws Exception {
        Path blf = BlfBinaryFixtures.classic(
                tempDir.resolve("discovered.blf"), 0, 0x100, BlfBinaryFixtures.enginePayload(2500.0, 50, 1),
                1_000_000_000L);
        MeasurementDecodePipeline discovered = MeasurementDecodePipeline.createDefault();
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = discovered.process(blf, database, ReaderContext.ofBatchSize(8), signals::addAll);
        assertEquals(1, result.statistics().sourceFramesRead());
        assertEquals(2500.0, find(signals, "EngineSpeed").physicalValue(), 1e-9);
    }

    @Test
    void graphQueryResolvesBlfDecodedSamples() throws Exception {
        Path blf = BlfBinaryFixtures.classic(
                tempDir.resolve("graph.blf"), 0, 0x100, BlfBinaryFixtures.enginePayload(2500.0, 50, 1),
                1_000_000_000L);
        Path storeRoot = tempDir.resolve("graph-store");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                storeRoot, "graph.blf", "pipeline.dbc")) {
            ProcessingResult result = pipeline.process(reader, blf, database, ReaderContext.ofBatchSize(8), store);
            store.checkpoint();
            assertEquals(1, result.statistics().sourceFramesRead());
            SignalCatalogEntry speed = store.snapshot().entries().stream()
                    .filter(e -> "EngineSpeed".equals(e.signalName()))
                    .findFirst()
                    .orElseThrow();
            List<SignalSamplePoint> samples = store.graphQuery().queryRange(speed.key(), 0L, Long.MAX_VALUE);
            assertEquals(1, samples.size());
            assertEquals(1_000_000_000L, samples.getFirst().timestampNanos());
            assertEquals(2500.0, samples.getFirst().physicalValue(), 1e-9);
        }
    }

    @Test
    void mixedAscAndBlfDoNotSharePhysicalIdentity() throws Exception {
        Path blf = BlfBinaryFixtures.classic(
                tempDir.resolve("mix.blf"), 1, 0x100, BlfBinaryFixtures.enginePayload(2500.0, 50, 1), 9_000L);
        Path asc = tempDir.resolve("mix.asc");
        java.nio.file.Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  1.000000 3 100 Rx d 8 10 27 5A 01 00 00 00 00
                """);
        List<CanonicalSignal> fromBlf = new ArrayList<>();
        List<CanonicalSignal> fromAsc = new ArrayList<>();
        MeasurementDecodePipeline discovered = MeasurementDecodePipeline.createDefault();
        discovered.process(blf, database, ReaderContext.ofBatchSize(8), fromBlf::addAll);
        discovered.process(asc, database, ReaderContext.ofBatchSize(8), fromAsc::addAll);
        assertEquals(1, find(fromBlf, "EngineSpeed").channel());
        assertEquals(3, find(fromAsc, "EngineSpeed").channel());
        assertEquals(2500.0, find(fromBlf, "EngineSpeed").physicalValue(), 1e-9);
        assertEquals(2500.0, find(fromAsc, "EngineSpeed").physicalValue(), 1e-9);
    }

    @Test
    void canGpsSignalsAreDiscoveredByExistingGpsIndex() throws Exception {
        Path dbcPath = tempDir.resolve("gps.dbc");
        java.nio.file.Files.writeString(dbcPath, """
                VERSION ""
                NS_ :
                BS_:
                BU_: GPS
                BO_ 256 GpsFix: 8 GPS
                 SG_ LATITUDE : 0|32@1- (0.000001,0) [-90|90] "deg" GPS
                 SG_ LONGITUDE : 32|32@1- (0.000001,0) [-180|180] "deg" GPS
                """);
        DbcDatabase gpsDbc = new DbcParser().parse(dbcPath);
        int latRaw = 51_507_400;
        int lonRaw = -12_345_600;
        byte[] payload = new byte[8];
        payload[0] = (byte) (latRaw);
        payload[1] = (byte) (latRaw >> 8);
        payload[2] = (byte) (latRaw >> 16);
        payload[3] = (byte) (latRaw >> 24);
        payload[4] = (byte) (lonRaw);
        payload[5] = (byte) (lonRaw >> 8);
        payload[6] = (byte) (lonRaw >> 16);
        payload[7] = (byte) (lonRaw >> 24);
        Path blf = BlfBinaryFixtures.classic(tempDir.resolve("gps.blf"), 1, 0x100, payload, 50_000L);
        Path storeRoot = tempDir.resolve("gps-store");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                storeRoot, "gps.blf", "gps.dbc")) {
            pipeline.process(reader, blf, gpsDbc, ReaderContext.ofBatchSize(8), store);
            store.checkpoint();
            GpsLocationSignalIndex index = GpsLocationSignalIndex.build(store.snapshot().entries());
            assertTrue(index.hasRoute(), "existing GPS discovery must see DBC-decoded lat/lon");
            SignalCatalogEntry lat = store.snapshot().entries().stream()
                    .filter(e -> "LATITUDE".equals(e.signalName()))
                    .findFirst()
                    .orElseThrow();
            List<SignalSamplePoint> samples = store.graphQuery().queryRange(lat.key(), 0L, Long.MAX_VALUE);
            assertEquals(1, samples.size());
            assertEquals(51.5074, samples.getFirst().physicalValue(), 1e-6);
        }
    }

    private static CanonicalSignal find(List<CanonicalSignal> signals, String name) {
        return signals.stream().filter(s -> name.equals(s.signalName())).findFirst().orElseThrow();
    }

    private static void assertArrayEqualsPayload(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "payload[" + i + "]");
        }
    }
}
