package com.stellar.platform.testkit;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcDiagnosticCode;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.EnumeratedState;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.export.AlignedSignalCsvExporter;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One simple {@code M}/{@code mN} message, written as the same payloads in MF4, BLF, and ASC.
 * Expected numbers are the DBC formula applied to those bytes. They are not taken from another export.
 */
class SimpleMuxRecordingParityTest {

    private static final long CAN_ID = 0x610L;
    private static final int CHANNEL = 1;
    private static final String DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            BO_ 1552 PowerMux: 8 ECU
             SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
             SG_ Voltage : 8|8@1+ (0.1,0) [0|25.5] "V" ECU
             SG_ Speed m0 : 16|16@1+ (0.5,0) [0|1000] "km/h" ECU
             SG_ Current m1 : 16|16@1- (0.1,-40) [-400|400] "A" ECU
             SG_ Torque m2 : 31|8@0+ (1,-10) [-10|245] "Nm" ECU
            VAL_ 1552 Selector 0 "Sleep" 1 "Drive" 2 "Charge" ;
            BA_DEF_ SG_ "GenSigInvalidValue" INT 0 0;
            BA_DEF_ SG_ "GenSigInactiveValue" INT 0 0;
            BA_ "GenSigInvalidValue" SG_ 1552 Speed 65535;
            BA_ "GenSigInactiveValue" SG_ 1552 Current 32767;
            """;

    /**
     * Byte formulas, independent of the decoder:
     * selector = byte0; voltage = byte1 * 0.1;
     * speed = (byte2 + byte3*256) * 0.5 when selector is 0;
     * current = signed16(byte2, byte3) * 0.1 - 40 when selector is 1;
     * torque = byte3 - 10 when selector is 2 (Motorola start bit 31, 8 bits).
     */
    private static final List<ExpectedFrame> FRAMES = List.of(
            frame(100_000_000L, bytes(0x00, 0x7D, 0x64, 0x00), Set.of(),
                    signal("Selector", 0, 0.0, "Sleep", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Speed", 100, 50.0, null, SignalValidity.VALID)),
            frame(200_000_000L, bytes(0x00, 0x7D, 0x64, 0x00), Set.of(),
                    signal("Selector", 0, 0.0, "Sleep", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Speed", 100, 50.0, null, SignalValidity.VALID)),
            frame(300_000_000L, bytes(0x01, 0x7D, 0x38, 0xFF), Set.of(),
                    signal("Selector", 1, 1.0, "Drive", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Current", -200, -200 * 0.1 - 40, null, SignalValidity.VALID)),
            frame(400_000_000L, bytes(0x02, 0x7D, 0x00, 0x32), Set.of(),
                    signal("Selector", 2, 2.0, "Charge", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Torque", 50, 40.0, null, SignalValidity.VALID)),
            frame(500_000_000L, bytes(0x09, 0x7D, 0x11, 0x22),
                    EnumSet.of(DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF),
                    signal("Selector", 9, 9.0, null, SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID)),
            frame(600_000_000L, bytes(0x00, 0x7D, 0xFF, 0xFF),
                    EnumSet.of(DbcDiagnosticCode.INVALID_RAW_VALUE, DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE),
                    signal("Selector", 0, 0.0, "Sleep", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Speed", 65535, 65535 * 0.5, "invalid", SignalValidity.INVALID_RAW_CODE)),
            frame(700_000_000L, bytes(0x01, 0x7D, 0xFF, 0x7F),
                    EnumSet.of(DbcDiagnosticCode.INVALID_RAW_VALUE, DbcDiagnosticCode.OUT_OF_RANGE_PHYSICAL_VALUE),
                    signal("Selector", 1, 1.0, "Drive", SignalValidity.VALID),
                    signal("Voltage", 125, 12.5, null, SignalValidity.VALID),
                    signal("Current", 32767, 32767 * 0.1 - 40, "inactive", SignalValidity.INACTIVE_RAW_CODE)),
            frame(800_000_000L, new byte[] {0x00},
                    EnumSet.of(DbcDiagnosticCode.INSUFFICIENT_PAYLOAD, DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF),
                    signal("Selector", 0, 0.0, "Sleep", SignalValidity.VALID)),
            frame(900_000_000L, new byte[0],
                    EnumSet.of(
                            DbcDiagnosticCode.INSUFFICIENT_PAYLOAD,
                            DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING,
                            DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF))
    );

    @TempDir
    Path temp;

    @Test
    void mf4BlfAndAscMatchTheSameManifest() throws Exception {
        DbcDatabase database = new DbcParser().parse(writeDbc(temp.resolve("power.dbc")));
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();

        assertMatches(pipeline, decoder, database, new Mf4FileReader(), writeMf4(temp.resolve("power.mf4")), "MF4");
        assertMatches(pipeline, decoder, database, blfReader(), writeBlf(temp.resolve("power.blf")), "BLF");
        assertMatches(pipeline, decoder, database, new AscFileReader(), writeAsc(temp.resolve("power.asc")), "ASC");
    }

    @Test
    void catalogCsvAndReopenKeepActiveBranchesAndNumericCells() throws Exception {
        DbcDatabase database = new DbcParser().parse(writeDbc(temp.resolve("power.dbc")));
        Path asc = writeAsc(temp.resolve("power.asc"));
        Path root = temp.resolve("store");
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(root, "power.asc", "power.dbc")) {
            pipeline.process(new AscFileReader(), asc, database, ReaderContext.ofBatchSize(16), store);
            store.checkpoint();

            assertEquals(3, count(store, "Speed"));
            assertEquals(2, count(store, "Current"));
            assertEquals(1, count(store, "Torque"));
            assertEquals(8, count(store, "Selector"));
            assertEquals(7, count(store, "Voltage"));
            assertEquals(0, count(store, "MissingLeaf"));

            SignalCatalogEntry selector = entry(store, "Selector");
            SignalSamplePoint sleep = store.graphQuery().queryRange(selector.key(), 100_000_000L, 100_000_000L).getFirst();
            assertEquals(0.0, sleep.physicalValue(), 0.0);
            assertEquals(0L, sleep.rawValue());
            assertEquals("Sleep", sleep.textualValue());
            assertNull(CategoricalValue.label(sleep.validity(), sleep.physicalValue(), sleep.textualValue()));
            assertEquals("Sleep", EnumeratedState.text(sleep.validity(), sleep.physicalValue(), sleep.textualValue()));

            Path csv = temp.resolve("power.csv");
            new AlignedSignalCsvExporter().export(
                    store,
                    List.of(entry(store, "Selector"), entry(store, "Speed"), entry(store, "Current")),
                    AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                    csv,
                    new AtomicBoolean());
            String text = Files.readString(csv);
            assertFalse(text.contains("Sleep"));
            assertFalse(text.contains("Drive"));
            assertFalse(text.contains("Charge"));
            assertTrue(text.contains("0.0"));
            assertTrue(text.contains("50.0"));
            assertTrue(text.contains("-60.0"));
            String driveRow = rowAt(text, 300_000_000L);
            assertTrue(driveRow.contains(",1.0,,-60.0"), driveRow);
        }

        try (GraphSignalSessionStore reopened = GraphSignalSessionStore.openExisting(root)) {
            SignalSamplePoint sleep = reopened.graphQuery()
                    .queryRange(entry(reopened, "Selector").key(), 100_000_000L, 100_000_000L)
                    .getFirst();
            assertEquals(100_000_000L, sleep.timestampNanos());
            assertEquals(0L, sleep.rawValue());
            assertEquals(0.0, sleep.physicalValue(), 0.0);
            assertEquals("Sleep", sleep.textualValue());
            assertEquals(50.0, reopened.graphQuery()
                    .queryRange(entry(reopened, "Speed").key(), 100_000_000L, 100_000_000L)
                    .getFirst().physicalValue(), 0.0);
            assertTrue(reopened.graphQuery()
                    .queryRange(entry(reopened, "Current").key(), 100_000_000L, 200_000_000L)
                    .isEmpty());
        }
    }

    private static void assertMatches(
            MeasurementDecodePipeline pipeline,
            DefaultDbcDecoder decoder,
            DbcDatabase database,
            AutomotiveFileReader reader,
            Path recording,
            String format
    ) throws Exception {
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(recording, ReaderContext.ofBatchSize(16), frames::addAll, warning -> { });
        assertEquals(FRAMES.size(), frames.size(), format + " frame count");
        for (int i = 0; i < FRAMES.size(); i++) {
            ExpectedFrame expected = FRAMES.get(i);
            CanonicalCanFrame frame = frames.get(i);
            assertEquals(expected.timestampNanos, frame.timestampNanos(), format + " timestamp " + i);
            assertEquals(CAN_ID, frame.canId(), format + " id " + i);
            assertEquals(CHANNEL, frame.channel(), format + " channel " + i);
            assertEquals(expected.payload.length, frame.dlc(), format + " dlc " + i);
            assertArrayEquals(expected.payload, frame.data(), format + " payload " + i);

            Set<DbcDiagnosticCode> codes = EnumSet.noneOf(DbcDiagnosticCode.class);
            decoder.decode(frame, database).diagnostics().forEach(d -> codes.add(d.code()));
            assertEquals(expected.diagnostics, codes, format + " diagnostics " + i + " " + frame.data().length);
        }

        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                reader, recording, database, ReaderContext.ofBatchSize(16), signals::addAll);
        assertEquals(FRAMES.size(), result.statistics().sourceFramesRead(), format);
        Map<Long, List<CanonicalSignal>> byTime = new LinkedHashMap<>();
        for (CanonicalSignal signal : signals) {
            byTime.computeIfAbsent(signal.timestampNanos(), key -> new ArrayList<>()).add(signal);
        }
        int expectedSignals = 0;
        for (ExpectedFrame expected : FRAMES) {
            expectedSignals += expected.signals.size();
            List<CanonicalSignal> got = byTime.getOrDefault(expected.timestampNanos, List.of());
            assertEquals(expected.signals.size(), got.size(), format + " signals @" + expected.timestampNanos);
            for (ExpectedSignal want : expected.signals) {
                CanonicalSignal gotSignal = got.stream()
                        .filter(s -> want.name.equals(s.signalName()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(format + " missing " + want.name));
                assertEquals(want.raw, gotSignal.rawValue(), format + " " + want.name);
                assertEquals(want.physical, gotSignal.physicalValue(), 0.0, format + " " + want.name);
                assertEquals(want.text, gotSignal.textualValue(), format + " " + want.name);
                assertEquals(want.validity, gotSignal.validity(), format + " " + want.name);
                assertEquals(CAN_ID, gotSignal.canId());
                assertEquals(CHANNEL, gotSignal.channel());
                assertEquals(expected.timestampNanos, gotSignal.timestampNanos());
            }
        }
        assertEquals(expectedSignals, signals.size(), format + " emitted a sample that is not in the manifest");
        assertFalse(signals.stream().anyMatch(s -> "Speed".equals(s.signalName()) && s.timestampNanos() == 300_000_000L));
        assertFalse(signals.stream().anyMatch(s -> "Current".equals(s.signalName()) && s.timestampNanos() == 100_000_000L));
        assertFalse(signals.stream().anyMatch(s -> "Torque".equals(s.signalName()) && s.timestampNanos() != 400_000_000L));
    }

    private static Path writeDbc(Path path) throws IOException {
        Files.writeString(path, DBC, StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeAsc(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("date Thu Jan 01 12:00:00.000 am 2026\n");
        text.append("base hex timestamps absolute\n");
        for (ExpectedFrame frame : FRAMES) {
            text.append(String.format("  %d.%09d %d %X Rx d %d",
                    frame.timestampNanos / 1_000_000_000L,
                    frame.timestampNanos % 1_000_000_000L,
                    CHANNEL,
                    CAN_ID,
                    frame.payload.length));
            for (byte value : frame.payload) {
                text.append(String.format(" %02X", value));
            }
            text.append('\n');
        }
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeBlf(Path path) throws IOException {
        List<BlfBinaryFixtures.ClassicFrame> frames = new ArrayList<>();
        for (ExpectedFrame frame : FRAMES) {
            frames.add(new BlfBinaryFixtures.ClassicFrame(CHANNEL, (int) CAN_ID, frame.payload, frame.timestampNanos));
        }
        return BlfBinaryFixtures.classics(path, frames);
    }

    private static Path writeMf4(Path path) throws IOException {
        List<byte[]> records = new ArrayList<>();
        for (ExpectedFrame frame : FRAMES) {
            records.add(busRecord(frame.timestampNanos, frame.payload));
        }
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(path)) {
            builder.writeIdBlock("4.10", 410, 0);
            long hd = builder.writeHd(0, 0, 0);
            long head = writeCnChain(builder);
            long cg = builder.writeCg(0, head, 1, records.size(), 0, 23, 0);
            long dt = builder.writeDt(concat(records));
            long dg = builder.writeDg(0, cg, dt, 0);
            builder.patchLink(hd, 0, dg);
        }
        return path;
    }

    private static long writeCnChain(SyntheticMdf4Builder builder) throws IOException {
        long time = builder.writeCn(0, builder.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = builder.writeCn(time, builder.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = builder.writeCn(data, builder.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = builder.writeCn(dlc, builder.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long direction = builder.writeCn(id, builder.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return builder.writeCn(direction, builder.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] busRecord(long timestampNanos, byte[] payload) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) CHANNEL);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) CAN_ID);
        buf.put(6, (byte) payload.length);
        System.arraycopy(payload, 0, data, 7, payload.length);
        buf.putLong(15, timestampNanos);
        return data;
    }

    private static byte[] concat(List<byte[]> parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static AutomotiveFileReader blfReader() {
        return ParserRegistry.load().findReader(Path.of("probe.blf")).orElseThrow(
                () -> new IllegalStateException("BLF reader is not on the ServiceLoader classpath"));
    }

    private static int count(GraphSignalSessionStore store, String name) {
        return store.snapshot().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .mapToInt(entry -> (int) entry.sampleCount())
                .findFirst()
                .orElse(0);
    }

    private static SignalCatalogEntry entry(GraphSignalSessionStore store, String name) {
        return store.snapshot().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow();
    }

    private static String rowAt(String csv, long timestampNanos) {
        for (String line : csv.split("\r\n")) {
            if (line.contains("," + timestampNanos + ",")) {
                return line;
            }
        }
        throw new AssertionError("no CSV row at " + timestampNanos + "\n" + csv);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[8];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static ExpectedFrame frame(
            long timestampNanos, byte[] payload, Set<DbcDiagnosticCode> diagnostics, ExpectedSignal... signals) {
        return new ExpectedFrame(timestampNanos, payload, diagnostics, List.of(signals));
    }

    private static ExpectedSignal signal(
            String name, long raw, double physical, String text, SignalValidity validity) {
        return new ExpectedSignal(name, raw, physical, text, validity);
    }

    private record ExpectedFrame(
            long timestampNanos,
            byte[] payload,
            Set<DbcDiagnosticCode> diagnostics,
            List<ExpectedSignal> signals
    ) {
    }

    private record ExpectedSignal(
            String name, long raw, double physical, String text, SignalValidity validity) {
    }
}
