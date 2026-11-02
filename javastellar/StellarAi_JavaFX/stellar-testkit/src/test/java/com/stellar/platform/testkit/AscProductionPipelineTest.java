package com.stellar.platform.testkit;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASC production pipeline: multiple ASC files, DBC decode, no-DBC parse.
 * Mixed MF4+ASC parity is covered by {@link MeasurementPipelineIntegrationTest}.
 */
class AscProductionPipelineTest {

    private static DbcDatabase database;
    private static MeasurementDecodePipeline pipeline;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadDbc() throws Exception {
        Path dbc = Path.of(AscProductionPipelineTest.class.getClassLoader()
                .getResource("dbc/pipeline.dbc").toURI());
        database = new DbcParser().parse(dbc);
        pipeline = new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder());
    }

    @Test
    void twoAscFilesKeepSameCanIdAsSeparateProvenance() throws Exception {
        Path a = writeAsc(tempDir.resolve("a.asc"), 1, 0x100, enginePayload(2500.0, 50, 1));
        Path b = writeAsc(tempDir.resolve("b.asc"), 2, 0x100, enginePayload(1000.0, 60, 2));
        AscFileReader reader = new AscFileReader();
        List<CanonicalCanFrame> framesA = new ArrayList<>();
        List<CanonicalCanFrame> framesB = new ArrayList<>();
        reader.readFrames(a, ReaderContext.ofBatchSize(8), framesA::addAll, w -> { });
        reader.readFrames(b, ReaderContext.ofBatchSize(8), framesB::addAll, w -> { });
        assertEquals(0x100L, framesA.getFirst().canId());
        assertEquals(0x100L, framesB.getFirst().canId());
        assertEquals(1, framesA.getFirst().channel());
        assertEquals(2, framesB.getFirst().channel());
        assertNotEquals(framesA.getFirst().payloadByteAt(0), framesB.getFirst().payloadByteAt(0));

        List<CanonicalSignal> signalsA = new ArrayList<>();
        List<CanonicalSignal> signalsB = new ArrayList<>();
        pipeline.process(reader, a, database, ReaderContext.ofBatchSize(8), signalsA::addAll);
        pipeline.process(reader, b, database, ReaderContext.ofBatchSize(8), signalsB::addAll);
        CanonicalSignal speedA = find(signalsA, "EngineSpeed");
        CanonicalSignal speedB = find(signalsB, "EngineSpeed");
        assertEquals(2500.0, speedA.physicalValue(), 1e-9);
        assertEquals(1000.0, speedB.physicalValue(), 1e-9);
        assertEquals(1, speedA.channel());
        assertEquals(2, speedB.channel());
    }

    @Test
    void threeAscFilesRemainIndependent() throws Exception {
        AscFileReader reader = new AscFileReader();
        for (int i = 0; i < 3; i++) {
            Path path = writeAsc(tempDir.resolve("file" + i + ".asc"), i + 1, 0x100,
                    enginePayload(1000.0 + i * 100, 40 + i, i));
            List<CanonicalCanFrame> frames = new ArrayList<>();
            reader.readFrames(path, ReaderContext.ofBatchSize(4), frames::addAll, w -> { });
            assertEquals(1, frames.size());
            assertEquals(i + 1, frames.getFirst().channel());
        }
    }

    @Test
    void ascWithoutDbcStillParsesFrames() throws Exception {
        Path asc = writeAsc(tempDir.resolve("nodbc.asc"), 1, 0x7E8, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        List<CanonicalCanFrame> frames = new ArrayList<>();
        new AscFileReader().readFrames(asc, ReaderContext.ofBatchSize(4), frames::addAll, w -> { });
        assertEquals(1, frames.size());
        assertEquals(0x7E8L, frames.getFirst().canId());
        assertEquals(8, frames.getFirst().dlc());
    }

    @Test
    void goldenAscPlusDbcExactPhysicalValueAndTimestamp() throws Exception {
        Path asc = writeAsc(tempDir.resolve("golden.asc"), 0, 0x100, enginePayload(2500.0, 50, 1));
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new AscFileReader(), asc, database, ReaderContext.ofBatchSize(8), signals::addAll);
        assertEquals(1, result.statistics().sourceFramesRead());
        CanonicalSignal speed = find(signals, "EngineSpeed");
        assertEquals(1_000_000_000L, speed.timestampNanos());
        assertEquals(0x100L, speed.canId());
        assertEquals(0, speed.channel());
        assertEquals(2500.0, speed.physicalValue(), 1e-9);
        assertEquals("rpm", speed.unit());
        assertEquals(50.0, find(signals, "EngineTemp").physicalValue(), 1e-9);
        assertEquals(1.0, find(signals, "StatusFlags").physicalValue(), 1e-9);
    }

    @Test
    void serviceLoaderPipelineDiscoversAscReader() throws Exception {
        Path asc = writeAsc(tempDir.resolve("discovered.asc"), 0, 0x100, enginePayload(2500.0, 50, 1));
        MeasurementDecodePipeline discovered = MeasurementDecodePipeline.createDefault();
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = discovered.process(asc, database, ReaderContext.ofBatchSize(8), signals::addAll);
        assertEquals(1, result.statistics().sourceFramesRead());
        assertEquals(2500.0, find(signals, "EngineSpeed").physicalValue(), 1e-9);
    }

    @Test
    void graphQueryResolvesAscDecodedSamples() throws Exception {
        Path asc = writeAsc(tempDir.resolve("graph.asc"), 0, 0x100, enginePayload(2500.0, 50, 1));
        Path storeRoot = tempDir.resolve("graph-store");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                storeRoot, "graph.asc", "pipeline.dbc")) {
            ProcessingResult result = pipeline.process(
                    new AscFileReader(), asc, database, ReaderContext.ofBatchSize(8), store);
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
            assertEquals(1L, store.graphQuery().sampleCount(speed.key()));
        }
    }

    private static Path writeAsc(Path path, int channel, int canId, byte[] payload) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("date Thu Jan 01 12:00:00.000 am 2026\n");
        sb.append("base hex timestamps absolute\n");
        sb.append("  1.000000 ").append(channel).append(' ')
                .append(Integer.toHexString(canId)).append(" Rx d ").append(payload.length);
        for (byte value : payload) {
            sb.append(String.format(" %02X", value));
        }
        sb.append('\n');
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static byte[] enginePayload(double rpm, int tempC, int flags) {
        int speedRaw = (int) Math.round(rpm / 0.25);
        byte[] payload = new byte[8];
        payload[0] = (byte) (speedRaw & 0xFF);
        payload[1] = (byte) ((speedRaw >> 8) & 0xFF);
        payload[2] = (byte) ((tempC + 40) & 0xFF);
        payload[3] = (byte) (flags & 0xFF);
        return payload;
    }

    private static CanonicalSignal find(List<CanonicalSignal> signals, String name) {
        return signals.stream().filter(s -> name.equals(s.signalName())).findFirst().orElseThrow();
    }
}
