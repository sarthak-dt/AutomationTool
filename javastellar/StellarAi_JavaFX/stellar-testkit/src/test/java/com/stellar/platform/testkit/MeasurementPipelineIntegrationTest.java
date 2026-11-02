package com.stellar.platform.testkit;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStatistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quality gate: MF4/ASC → CanonicalCanFrame → Common DbcDecoder → CanonicalSignal.
 */
class MeasurementPipelineIntegrationTest {

    private static DbcDatabase database;
    private static MeasurementDecodePipeline pipeline;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadDbc() throws Exception {
        Path dbc = Path.of(MeasurementPipelineIntegrationTest.class.getClassLoader()
                .getResource("dbc/pipeline.dbc").toURI());
        database = new DbcParser().parse(dbc);
        pipeline = new MeasurementDecodePipeline(new ParserRegistry(List.of()), new DefaultDbcDecoder());
    }

    @Test
    void finalizedMf4_endToEnd_exactSignals() throws Exception {
        Path mf4 = tempDir.resolve("finalized-can.mf4");
        writeClassicMf4(mf4, 1, List.of(busRecord(1_000_000_000L, 0x100, enginePayload(2500.0, 50, 1))), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(8), signals::addAll);

        assertEquals(1, result.statistics().sourceFramesRead());
        assertEquals(1, result.statistics().validFrames());
        assertEquals(0, result.statistics().recoveredFrames());
        assertEquals(3, signals.size());

        CanonicalSignal speed = find(signals, "EngineSpeed");
        assertEquals("EngineData", speed.messageName());
        assertEquals(1_000_000_000L, speed.timestampNanos());
        assertEquals(0x100L, speed.canId());
        assertEquals(0, speed.channel());
        assertEquals(10000L, speed.rawValue());
        assertEquals(2500.0, speed.physicalValue(), 1e-9);
        assertEquals("rpm", speed.unit());
        assertTrue(speed.sourceReference().sourceByteOffset() >= 0);
        assertEquals(50.0, find(signals, "EngineTemp").physicalValue(), 1e-9);
        assertEquals(1.0, find(signals, "StatusFlags").physicalValue(), 1e-9);
        assertTrue(result.completedSuccessfully());
    }

    /**
     * Permanent POC regression: cycleCount=0 + physical data → recovered frames → DBC signals.
     */
    @Test
    void pocRegression_unfinalizedZeroCycle_mf4ToSignals_notSilentZero() throws Exception {
        Path mf4 = tempDir.resolve("unfinalized-zero-cycle.mf4");
        writeClassicMf4(mf4, 0, List.of(
                busRecord(1_000L, 0x100, enginePayload(2500.0, 50, 1)),
                busRecord(2_000L, 0x100, enginePayload(1000.0, 60, 2))
        ), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        assertTrue(result.statistics().sourceFramesRead() > 0, "frames must not be zero");
        assertTrue(result.statistics().signalsEmitted() > 0, "signals must not be zero");
        assertEquals(2, result.statistics().sourceFramesRead());
        assertEquals(2, result.statistics().recoveredFrames());
        assertEquals(6, signals.size());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                "READER".equals(d.layer()) && (d.code().contains("RECOVERY")
                        || d.message().contains("RECOVERY")
                        || d.message().contains("ZERO_DECLARED"))));
        assertTrue(signals.stream().allMatch(s -> s.sourceReference().sourceByteOffset() >= 0));
        assertEquals(2500.0, find(signals, "EngineSpeed").physicalValue(), 1e-9);
    }

    @Test
    void ascMf4Parity_sameDbc_samePhysicalValues() throws Exception {
        Path asc = tempDir.resolve("equivalent-can.asc");
        Path mf4 = tempDir.resolve("equivalent-can.mf4");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  1.000000 0 100 Rx d 8 10 27 5A 01 00 00 00 00
                """, StandardCharsets.UTF_8);
        writeClassicMf4(mf4, 1, List.of(busRecord(1_000_000_000L, 0x100, enginePayload(2500.0, 50, 1))), 0);

        List<CanonicalSignal> ascSignals = new ArrayList<>();
        pipeline.process(new AscFileReader(), asc, database, ReaderContext.ofBatchSize(8), ascSignals::addAll);

        List<CanonicalSignal> mf4Signals = new ArrayList<>();
        pipeline.process(new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(8), mf4Signals::addAll);

        CanonicalSignal ascSpeed = find(ascSignals, "EngineSpeed");
        CanonicalSignal mf4Speed = find(mf4Signals, "EngineSpeed");
        assertEquals(ascSpeed.canId(), mf4Speed.canId());
        assertEquals(ascSpeed.signalName(), mf4Speed.signalName());
        assertEquals(ascSpeed.rawValue(), mf4Speed.rawValue());
        assertEquals(ascSpeed.physicalValue(), mf4Speed.physicalValue(), 1e-9);
        assertEquals(ascSpeed.unit(), mf4Speed.unit());
    }

    @Test
    void standardAndExtendedCan_matchCorrectMessages() throws Exception {
        Path mf4 = tempDir.resolve("std-ext.mf4");
        writeMultiIdMf4(
                mf4,
                List.of(busRecord(10L, 0x100, enginePayload(2500.0, 50, 0))),
                List.of(busRecord(20L, 0x100, chassisPayload(12.34, 56.78)))
        );

        List<CanonicalSignal> signals = new ArrayList<>();
        pipeline.process(new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(8), signals::addAll);

        assertTrue(signals.stream().anyMatch(s -> "EngineSpeed".equals(s.signalName())));
        assertTrue(signals.stream().anyMatch(s -> "WheelSpeedFL".equals(s.signalName())));
        assertEquals(0x100L, find(signals, "EngineSpeed").canId());
        assertEquals(0x100L, find(signals, "WheelSpeedFL").canId());
    }

    @Test
    void canFd_endToEnd_signalBeyondByte8() throws Exception {
        Path mf4 = tempDir.resolve("canfd.mf4");
        writeFdMf4(mf4, 0x700, fdPayload(0x1234, 200));

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        assertTrue(result.statistics().sourceFramesRead() >= 1);
        CanonicalSignal sensorB = find(signals, "SensorB");
        assertEquals(100.0, sensorB.physicalValue(), 1e-9);
        assertEquals("V", sensorB.unit());
        assertEquals(0x700L, sensorB.canId());
    }

    @Test
    void multiplexing_emitsOnlyActiveBranch() throws Exception {
        Path mf4 = tempDir.resolve("mux.mf4");
        writeClassicMf4(mf4, 1, List.of(busRecord(1L, 0x600, new byte[]{0x00, 42})), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        pipeline.process(new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        Set<String> names = signals.stream().map(CanonicalSignal::signalName).collect(Collectors.toSet());
        assertTrue(names.contains("Mode"));
        assertTrue(names.contains("Speed"));
        assertFalse(names.contains("Temperature"));
        assertEquals(42.0, find(signals, "Speed").physicalValue(), 1e-9);
    }

    @Test
    void valueTable_offOn() throws Exception {
        Path mf4 = tempDir.resolve("val.mf4");
        writeClassicMf4(mf4, 2, List.of(
                busRecord(1L, 0x500, new byte[]{0x00}),
                busRecord(2L, 0x500, new byte[]{0x01})
        ), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        pipeline.process(new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        List<CanonicalSignal> switches = signals.stream().filter(s -> "Switch".equals(s.signalName())).toList();
        assertEquals(2, switches.size());
        assertEquals("OFF", switches.get(0).textualValue());
        assertEquals("ON", switches.get(1).textualValue());
    }

    @Test
    void batching_noLossNoDuplicate() throws Exception {
        Path mf4 = tempDir.resolve("batch.mf4");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(busRecord(1000L * i, 0x100, enginePayload(1000.0 + i, 40, i)));
        }
        writeClassicMf4(mf4, 5, records, 0);

        AtomicInteger batches = new AtomicInteger();
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(),
                mf4,
                database,
                ReaderContext.ofBatchSize(2),
                batch -> {
                    batches.incrementAndGet();
                    signals.addAll(batch);
                });

        assertEquals(5, result.statistics().sourceFramesRead());
        assertEquals(15, signals.size());
        assertTrue(batches.get() >= 2);
        assertEquals(15, result.statistics().signalsEmitted());
    }

    @Test
    void cancellation_stopsSafely() throws Exception {
        Path mf4 = tempDir.resolve("cancel.mf4");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(busRecord(i, 0x100, enginePayload(2500.0, 50, 1)));
        }
        writeClassicMf4(mf4, 5, records, 0);

        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(),
                mf4,
                database,
                new ReaderContext(2, token),
                signals::addAll);

        assertTrue(result.cancelled() || signals.size() < 15);
    }

    @Test
    void unmatchedCanId_preservesDbcDiagnostics() throws Exception {
        Path mf4 = tempDir.resolve("unmatched.mf4");
        writeClassicMf4(mf4, 1, List.of(busRecord(1L, 0x111, new byte[8])), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        assertEquals(0, signals.size());
        assertEquals(1, result.statistics().dbcUnmatchedFrames());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                "DBC".equals(d.layer()) && d.code().contains("UNMATCHED")));
    }

    @Test
    void noSilentZero_invariantForRecoverableUnfinalized() throws Exception {
        Path mf4 = tempDir.resolve("no-silent-zero.mf4");
        writeClassicMf4(mf4, 0, List.of(busRecord(1L, 0x100, enginePayload(2500.0, 50, 1))), 0);

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(4), signals::addAll);

        boolean emptySuccess = result.completedSuccessfully()
                && result.statistics().sourceFramesRead() == 0
                && result.statistics().signalsEmitted() == 0
                && result.diagnostics().stream().noneMatch(d ->
                d.code().contains("NOT_RECOVERABLE") || d.code().contains("FATAL"));
        assertFalse(emptySuccess);
        assertTrue(result.statistics().sourceFramesRead() > 0);
        assertTrue(result.statistics().signalsEmitted() > 0);
    }

    @Test
    void processingStatistics_exampleShape() throws Exception {
        Path mf4 = tempDir.resolve("stats.mf4");
        writeClassicMf4(mf4, 1, List.of(busRecord(1L, 0x100, enginePayload(2500.0, 50, 1))), 0);
        ProcessingResult result = pipeline.process(
                new Mf4FileReader(), mf4, database, ReaderContext.ofBatchSize(8), batch -> {
                });
        ProcessingStatistics s = result.statistics();
        assertNotNull(s.toString());
        assertEquals(1, s.sourceFramesRead());
        assertEquals(1, s.validFrames());
        assertEquals(1, s.dbcMatchedFrames());
        assertEquals(3, s.signalsEmitted());
    }

    private static CanonicalSignal find(List<CanonicalSignal> signals, String name) {
        return signals.stream().filter(s -> name.equals(s.signalName())).findFirst().orElseThrow();
    }

    private static byte[] enginePayload(double rpm, int tempC, int flags) {
        int speedRaw = (int) Math.round(rpm / 0.25);
        int tempRaw = tempC + 40;
        byte[] p = new byte[8];
        p[0] = (byte) (speedRaw & 0xFF);
        p[1] = (byte) ((speedRaw >> 8) & 0xFF);
        p[2] = (byte) (tempRaw & 0xFF);
        p[3] = (byte) (flags & 0xFF);
        return p;
    }

    private static byte[] chassisPayload(double fl, double fr) {
        int flRaw = (int) Math.round(fl / 0.01);
        int frRaw = (int) Math.round(fr / 0.01);
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) flRaw);
        buf.putShort((short) frRaw);
        return buf.array();
    }

    private static byte[] fdPayload(int sensorA, int sensorBRaw) {
        byte[] p = new byte[16];
        p[0] = (byte) (sensorA & 0xFF);
        p[1] = (byte) ((sensorA >> 8) & 0xFF);
        p[8] = (byte) (sensorBRaw & 0xFF);
        p[9] = (byte) ((sensorBRaw >> 8) & 0xFF);
        return p;
    }

    private static byte[] busRecord(long timestampNanos, long canId, byte[] payload) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
        int dlc = Math.min(8, payload.length);
        buf.put(6, (byte) dlc);
        System.arraycopy(payload, 0, data, 7, dlc);
        buf.putLong(15, timestampNanos);
        return data;
    }

    private void writeClassicMf4(Path file, long cycles, List<byte[]> records, int unfin) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b, false);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            long dt = b.writeDt(concat(records));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private void writeMultiIdMf4(Path file, List<byte[]> standardRecords, List<byte[]> extendedRecords)
            throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long extHead = writeCnChain(b, true);
            long cgExt = b.writeCg(0, extHead, 2, extendedRecords.size(), 0, 23, 0);
            long stdHead = writeCnChain(b, false);
            long cgStd = b.writeCg(cgExt, stdHead, 1, standardRecords.size(), 0, 23, 0);
            List<byte[]> all = new ArrayList<>();
            for (byte[] r : standardRecords) {
                all.add(prepend((byte) 1, r));
            }
            for (byte[] r : extendedRecords) {
                all.add(prepend((byte) 2, r));
            }
            long dt = b.writeDt(concat(all));
            long dg = b.writeDg(0, cgStd, dt, 1);
            b.patchLink(hd, 0, dg);
        }
    }

    private void writeFdMf4(Path file, long canId, byte[] payload16) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            int dataBytes = 78;
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 70, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 6, 0, 512, 0);
            long dlcCn = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 5, 0, 8, 0);
            long fd = b.writeCn(dlcCn, b.writeTx("CAN_FD"), 0, 0, 0, 0, 4, 0, 8, 0);
            long id = b.writeCn(fd, b.writeTx("CAN_ID"), 0, 0, 0, 0, 0, 0, 11, 0);
            long cg = b.writeCg(0, id, 1, 1, 0, dataBytes, 0);
            ByteBuffer buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt((int) canId);
            buf.put((byte) 1);
            buf.put((byte) 16);
            byte[] p = new byte[64];
            System.arraycopy(payload16, 0, p, 0, Math.min(16, payload16.length));
            buf.put(p);
            buf.putLong(1_000L);
            long dt = b.writeDt(buf.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static long writeCnChain(SyntheticMdf4Builder b, boolean extendedId) throws IOException {
        int idBits = extendedId ? 29 : 11;
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, idBits, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] prepend(byte id, byte[] record) {
        byte[] out = new byte[1 + record.length];
        out[0] = id;
        System.arraycopy(record, 0, out, 1, record.length);
        return out;
    }

    private static byte[] concat(List<byte[]> parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
