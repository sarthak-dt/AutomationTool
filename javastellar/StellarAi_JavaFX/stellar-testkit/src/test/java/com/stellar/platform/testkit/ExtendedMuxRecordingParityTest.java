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
 * Extended {@code SG_MUL_VAL_} frames, written identically into MF4, BLF, and ASC.
 * Expected numbers are raw × factor + offset. They are not copied from another export.
 */
class ExtendedMuxRecordingParityTest {

    private static final int CHANNEL = 1;
    private static final long EXT = 0x620L;
    private static final long AMBIGUOUS = 0x622L;
    private static final long CYCLE = 0x623L;
    private static final String DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            BO_ 1568 ExtMux: 8 ECU
             SG_ Group M : 0|8@1+ (1,0) [0|255] "" ECU
             SG_ Branch m1 : 8|8@1+ (1,0) [0|255] "" ECU
             SG_ Voltage : 16|8@1+ (0.1,0) [0|25.5] "V" ECU
             SG_ Current m2 : 24|16@1- (0.1,-40) [-400|400] "A" ECU
             SG_ Speed m10 : 40|8@1+ (1,0) [0|255] "km/h" ECU
             SG_ Torque m0 : 55|8@0+ (1,-10) [-10|245] "Nm" ECU
             SG_ Flag m11 : 56|8@1+ (1,0) [0|255] "" ECU
            SG_MUL_VAL_ 1568 Branch Group 1-1;
            SG_MUL_VAL_ 1568 Current Group 1-1;
            SG_MUL_VAL_ 1568 Current Branch 2-2;
            SG_MUL_VAL_ 1568 Speed Group 10-12, 20-20;
            SG_MUL_VAL_ 1568 Torque Group 0-0;
            SG_MUL_VAL_ 1568 Flag Group 11-11;
            VAL_ 1568 Group 0 "Sleep" 1 "Drive" 10 "Low" 11 "Mid" 12 "High" 20 "Peak";
            BO_ 1570 AmbiguousMux: 8 ECU
             SG_ Left M : 0|8@1+ (1,0) [0|255] "" ECU
             SG_ Right M : 8|8@1+ (1,0) [0|255] "" ECU
             SG_ Leaf m0 : 16|8@1+ (1,0) [0|255] "" ECU
             SG_ Keep : 24|8@1+ (1,0) [0|255] "" ECU
            BO_ 1571 CycleMux: 4 ECU
             SG_ Alpha M : 0|8@1+ (1,0) [0|255] "" ECU
             SG_ Beta m1 : 8|8@1+ (1,0) [0|255] "" ECU
             SG_ Held : 16|8@1+ (1,0) [0|255] "" ECU
            SG_MUL_VAL_ 1571 Beta Alpha 1-1;
            SG_MUL_VAL_ 1571 Alpha Beta 1-1;
            """;

    private static final List<ExpectedFrame> FRAMES = List.of(
            frame(100_000_000L, EXT, bytes(0x00, 0x09, 0x64, 0x00, 0x00, 0x00, 0x1E, 0x00), Set.of(), null,
                    signal("Group", 0, 0.0, "Sleep", SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Torque", 30, 20.0, null, SignalValidity.VALID)),
            frame(200_000_000L, EXT, bytes(0x01, 0x02, 0x64, 0x38, 0xFF, 0x00, 0x00, 0x00), Set.of(), null,
                    signal("Group", 1, 1.0, "Drive", SignalValidity.VALID),
                    signal("Branch", 2, 2.0, null, SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Current", -200, -200 * 0.1 - 40, null, SignalValidity.VALID)),
            frame(300_000_000L, EXT, bytes(0x01, 0x00, 0x64, 0x11, 0x22, 0x33, 0x44, 0x55),
                    EnumSet.of(DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF), null,
                    signal("Group", 1, 1.0, "Drive", SignalValidity.VALID),
                    signal("Branch", 0, 0.0, null, SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID)),
            frame(400_000_000L, EXT, bytes(0x0A, 0x00, 0x64, 0x00, 0x00, 0x28, 0x00, 0x00), Set.of(), null,
                    signal("Group", 10, 10.0, "Low", SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Speed", 40, 40.0, null, SignalValidity.VALID)),
            frame(500_000_000L, EXT, bytes(0x0C, 0x00, 0x64, 0x00, 0x00, 0x29, 0x00, 0x00), Set.of(), null,
                    signal("Group", 12, 12.0, "High", SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Speed", 41, 41.0, null, SignalValidity.VALID)),
            frame(600_000_000L, EXT, bytes(0x0B, 0x00, 0x64, 0x00, 0x00, 0x2A, 0x00, 0x07), Set.of(), null,
                    signal("Group", 11, 11.0, "Mid", SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Speed", 42, 42.0, null, SignalValidity.VALID),
                    signal("Flag", 7, 7.0, null, SignalValidity.VALID)),
            frame(700_000_000L, EXT, bytes(0x14, 0x00, 0x64, 0x00, 0x00, 0x0F, 0x00, 0x00), Set.of(), null,
                    signal("Group", 20, 20.0, "Peak", SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Speed", 15, 15.0, null, SignalValidity.VALID)),
            frame(800_000_000L, EXT, bytes(0x09, 0x01, 0x64, 0xFF, 0xFF, 0x01, 0x02, 0x03),
                    EnumSet.of(DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF), null,
                    signal("Group", 9, 9.0, null, SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID)),
            frame(900_000_000L, EXT, bytes(0x01, 0x02, 0x64, 0x0A, 0x00, 0x00, 0x00, 0x00), Set.of(), null,
                    signal("Group", 1, 1.0, "Drive", SignalValidity.VALID),
                    signal("Branch", 2, 2.0, null, SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID),
                    signal("Current", 10, 10 * 0.1 - 40, null, SignalValidity.VALID)),
            frame(1_000_000_000L, EXT, bytes(0x0D, 0x00, 0x64, 0x00, 0x00, 0x63, 0x00, 0x00),
                    EnumSet.of(DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF), null,
                    signal("Group", 13, 13.0, null, SignalValidity.VALID),
                    signal("Voltage", 100, 10.0, null, SignalValidity.VALID)),
            frame(1_100_000_000L, EXT, new byte[] {0x01},
                    EnumSet.of(DbcDiagnosticCode.INSUFFICIENT_PAYLOAD, DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF), null,
                    signal("Group", 1, 1.0, "Drive", SignalValidity.VALID)),
            frame(1_200_000_000L, EXT, new byte[0],
                    EnumSet.of(DbcDiagnosticCode.INSUFFICIENT_PAYLOAD, DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF), null),
            frame(1_300_000_000L, AMBIGUOUS, bytes(0x00, 0x01, 0x63, 0x05, 0x00, 0x00, 0x00, 0x00),
                    EnumSet.of(DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING, DbcDiagnosticCode.MUX_NO_ACTIVE_LEAF),
                    "independent multiplexors",
                    signal("Left", 0, 0.0, null, SignalValidity.VALID),
                    signal("Right", 1, 1.0, null, SignalValidity.VALID),
                    signal("Keep", 5, 5.0, null, SignalValidity.VALID)),
            frame(1_400_000_000L, CYCLE, new byte[] {0x01, 0x01, 0x05, 0x00},
                    EnumSet.of(DbcDiagnosticCode.UNSUPPORTED_MULTIPLEXING), "Cycle",
                    signal("Held", 5, 5.0, null, SignalValidity.VALID))
    );

    @TempDir
    Path temp;

    @Test
    void mf4BlfAndAscMatchTheExtendedManifest() throws Exception {
        DbcDatabase database = new DbcParser().parse(writeDbc(temp.resolve("ext.dbc")));
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        assertMatches(pipeline, decoder, database, new Mf4FileReader(), writeMf4(temp.resolve("ext.mf4")), "MF4");
        assertMatches(pipeline, decoder, database, blfReader(), writeBlf(temp.resolve("ext.blf")), "BLF");
        assertMatches(pipeline, decoder, database, new AscFileReader(), writeAsc(temp.resolve("ext.asc")), "ASC");
    }

    @Test
    void catalogCsvAndReopenKeepExtendedLeavesNumeric() throws Exception {
        DbcDatabase database = new DbcParser().parse(writeDbc(temp.resolve("ext.dbc")));
        Path asc = writeAsc(temp.resolve("ext.asc"));
        Path root = temp.resolve("store");
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(root, "ext.asc", "ext.dbc")) {
            pipeline.process(new AscFileReader(), asc, database, ReaderContext.ofBatchSize(32), store);
            store.checkpoint();
            assertEquals(11, count(store, "Group", EXT));
            assertEquals(10, count(store, "Voltage", EXT));
            assertEquals(3, count(store, "Branch", EXT));
            assertEquals(2, count(store, "Current", EXT));
            assertEquals(4, count(store, "Speed", EXT));
            assertEquals(1, count(store, "Torque", EXT));
            assertEquals(1, count(store, "Flag", EXT));
            assertEquals(0, count(store, "Leaf", AMBIGUOUS));
            assertEquals(0, count(store, "Alpha", CYCLE));
            assertEquals(0, count(store, "Beta", CYCLE));

            SignalSamplePoint sleep = store.graphQuery()
                    .queryRange(entry(store, "Group", EXT).key(), 100_000_000L, 100_000_000L).getFirst();
            assertEquals("Sleep", sleep.textualValue());
            assertNull(CategoricalValue.label(sleep.validity(), sleep.physicalValue(), sleep.textualValue()));
            assertEquals("Sleep", EnumeratedState.text(sleep.validity(), sleep.physicalValue(), sleep.textualValue()));

            Path csv = temp.resolve("ext.csv");
            new AlignedSignalCsvExporter().export(
                    store,
                    List.of(entry(store, "Group", EXT), entry(store, "Speed", EXT), entry(store, "Current", EXT)),
                    AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                    csv,
                    new AtomicBoolean());
            String text = Files.readString(csv);
            assertFalse(text.contains("Sleep"));
            assertFalse(text.contains("Drive"));
            assertTrue(text.contains("40.0"));
            assertTrue(text.contains("-60.0"));
            String nested = rowAt(text, 200_000_000L);
            assertTrue(nested.contains(",1.0,,"), nested);
        }
        try (GraphSignalSessionStore reopened = GraphSignalSessionStore.openExisting(root)) {
            SignalSamplePoint sleep = reopened.graphQuery()
                    .queryRange(entry(reopened, "Group", EXT).key(), 100_000_000L, 100_000_000L).getFirst();
            assertEquals(100_000_000L, sleep.timestampNanos());
            assertEquals(0L, sleep.rawValue());
            assertEquals("Sleep", sleep.textualValue());
            assertTrue(reopened.graphQuery()
                    .queryRange(entry(reopened, "Speed", EXT).key(), 100_000_000L, 300_000_000L).isEmpty());
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
        reader.readFrames(recording, ReaderContext.ofBatchSize(32), frames::addAll, warning -> { });
        assertEquals(FRAMES.size(), frames.size(), format + " frame count");
        for (int i = 0; i < FRAMES.size(); i++) {
            ExpectedFrame expected = FRAMES.get(i);
            CanonicalCanFrame frame = frames.get(i);
            assertEquals(expected.timestampNanos, frame.timestampNanos(), format + " time " + i);
            assertEquals(expected.canId, frame.canId(), format + " id " + i);
            assertEquals(CHANNEL, frame.channel(), format + " channel " + i);
            assertEquals(expected.payload.length, frame.dlc(), format + " dlc " + i);
            assertArrayEquals(expected.payload, frame.data(), format + " payload " + i);
            var decoded = decoder.decode(frame, database);
            Set<DbcDiagnosticCode> codes = EnumSet.noneOf(DbcDiagnosticCode.class);
            decoded.diagnostics().forEach(d -> codes.add(d.code()));
            assertEquals(expected.diagnostics, codes, format + " diagnostics " + i + " " + decoded.diagnostics());
            if (expected.diagnosticText != null) {
                assertTrue(decoded.diagnostics().stream().anyMatch(d -> d.message().contains(expected.diagnosticText)),
                        format + " " + decoded.diagnostics());
            }
        }
        List<CanonicalSignal> signals = new ArrayList<>();
        ProcessingResult result = pipeline.process(
                reader, recording, database, ReaderContext.ofBatchSize(32), signals::addAll);
        assertEquals(FRAMES.size(), result.statistics().sourceFramesRead(), format);
        Map<Long, List<CanonicalSignal>> byTime = new LinkedHashMap<>();
        for (CanonicalSignal signal : signals) {
            byTime.computeIfAbsent(signal.timestampNanos(), key -> new ArrayList<>()).add(signal);
        }
        int expectedSignals = 0;
        for (ExpectedFrame expected : FRAMES) {
            expectedSignals += expected.signals.size();
            List<CanonicalSignal> got = byTime.getOrDefault(expected.timestampNanos, List.of());
            assertEquals(expected.signals.size(), got.size(), format + " @" + expected.timestampNanos + " " + got);
            for (ExpectedSignal want : expected.signals) {
                CanonicalSignal gotSignal = got.stream().filter(s -> want.name.equals(s.signalName())).findFirst()
                        .orElseThrow(() -> new AssertionError(format + " missing " + want.name));
                assertEquals(want.raw, gotSignal.rawValue(), format + " " + want.name);
                assertEquals(want.physical, gotSignal.physicalValue(), 0.0, format + " " + want.name);
                assertEquals(want.text, gotSignal.textualValue(), format + " " + want.name);
                assertEquals(want.validity, gotSignal.validity(), format + " " + want.name);
                assertEquals(expected.canId, gotSignal.canId());
                assertEquals(CHANNEL, gotSignal.channel());
                assertEquals(expected.timestampNanos, gotSignal.timestampNanos());
            }
        }
        assertEquals(expectedSignals, signals.size(), format);
        assertFalse(signals.stream().anyMatch(s -> "Leaf".equals(s.signalName())));
        assertFalse(signals.stream().anyMatch(s -> "Alpha".equals(s.signalName()) || "Beta".equals(s.signalName())));
        assertFalse(signals.stream().anyMatch(s -> "Speed".equals(s.signalName()) && s.timestampNanos() == 200_000_000L));
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
                    CHANNEL, frame.canId, frame.payload.length));
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
            frames.add(new BlfBinaryFixtures.ClassicFrame(
                    CHANNEL, (int) frame.canId, frame.payload, frame.timestampNanos));
        }
        return BlfBinaryFixtures.classics(path, frames);
    }

    private static Path writeMf4(Path path) throws IOException {
        List<byte[]> records = new ArrayList<>();
        for (ExpectedFrame frame : FRAMES) {
            records.add(busRecord(frame.timestampNanos, frame.canId, frame.payload));
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

    private static byte[] busRecord(long timestampNanos, long canId, byte[] payload) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) CHANNEL);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
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
        return ParserRegistry.load().findReader(Path.of("probe.blf")).orElseThrow();
    }

    private static int count(GraphSignalSessionStore store, String name, long canId) {
        return store.snapshot().entries().stream()
                .filter(entry -> name.equals(entry.signalName()) && entry.key().canId() == canId)
                .mapToInt(entry -> (int) entry.sampleCount())
                .findFirst()
                .orElse(0);
    }

    private static SignalCatalogEntry entry(GraphSignalSessionStore store, String name, long canId) {
        return store.snapshot().entries().stream()
                .filter(entry -> name.equals(entry.signalName()) && entry.key().canId() == canId)
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
            long timestampNanos,
            long canId,
            byte[] payload,
            Set<DbcDiagnosticCode> diagnostics,
            String diagnosticText,
            ExpectedSignal... signals
    ) {
        return new ExpectedFrame(timestampNanos, canId, payload, diagnostics, diagnosticText, List.of(signals));
    }

    private static ExpectedSignal signal(
            String name, long raw, double physical, String text, SignalValidity validity) {
        return new ExpectedSignal(name, raw, physical, text, validity);
    }

    private record ExpectedFrame(
            long timestampNanos,
            long canId,
            byte[] payload,
            Set<DbcDiagnosticCode> diagnostics,
            String diagnosticText,
            List<ExpectedSignal> signals
    ) {
    }

    private record ExpectedSignal(String name, long raw, double physical, String text, SignalValidity validity) {
    }
}
