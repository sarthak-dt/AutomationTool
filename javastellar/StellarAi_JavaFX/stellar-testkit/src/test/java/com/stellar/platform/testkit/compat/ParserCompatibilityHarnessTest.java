package com.stellar.platform.testkit.compat;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserCompatibilityHarnessTest {

    @TempDir
    Path temp;

    @Test
    void producesBoundedReports_withFullSignalNamesAndMatchCounts() throws Exception {
        Path mf4 = temp.resolve("compat.mf4");
        writeClassicMf4(mf4, 2, List.of(
                busRecord(1_000L, 0x100, enginePayload(2500.0, 50, 1)),
                busRecord(2_000L, 0x111, new byte[8])
        ), 0);

        Path dbc = Path.of(getClass().getClassLoader().getResource("dbc/pipeline.dbc").toURI());
        Path out = temp.resolve("compatibility-report");

        CompatibilityHarnessRunner.run(new HarnessOptions(
                mf4, dbc, out, 2, 5, 2, true));

        assertTrue(Files.isRegularFile(out.resolve("00-summary.txt")));
        assertTrue(Files.isRegularFile(out.resolve("01-mf4-metadata.txt")));
        assertTrue(Files.isRegularFile(out.resolve("02-runtime-schemas.csv")));
        assertTrue(Files.isRegularFile(out.resolve("03-can-frame-samples.csv")));
        assertTrue(Files.isRegularFile(out.resolve("04-dbc-inventory.csv")));
        assertTrue(Files.isRegularFile(out.resolve("05-dbc-multiplex-graph.txt")));
        assertTrue(Files.isRegularFile(out.resolve("06-dbc-match-summary.csv")));
        assertTrue(Files.isRegularFile(out.resolve("07-mux-trace.csv")));
        assertTrue(Files.isRegularFile(out.resolve("08-canonical-signal-samples.csv")));
        assertTrue(Files.isRegularFile(out.resolve("09-signal-catalog.csv")));
        assertTrue(Files.isRegularFile(out.resolve("10-duplicate-analysis.txt")));
        assertTrue(Files.isRegularFile(out.resolve("11-native-mf4-channels.csv")));

        String inventory = Files.readString(out.resolve("04-dbc-inventory.csv"), StandardCharsets.UTF_8);
        assertTrue(inventory.contains("EngineSpeed"));
        assertTrue(inventory.contains("EngineTemp"));
        assertTrue(inventory.contains("StatusFlags"));

        String summary = Files.readString(out.resolve("00-summary.txt"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("matched frames=1") || summary.contains("matched frames=1\n"));
        assertTrue(summary.contains("unmatched frames=1") || summary.contains("unmatched frames=1\n"));

        long frameSampleRows = Files.lines(out.resolve("03-can-frame-samples.csv")).skip(1).filter(l -> !l.isBlank()).count();
        assertTrue(frameSampleRows <= 4, "frame samples must respect bound, was " + frameSampleRows);

        String match = Files.readString(out.resolve("06-dbc-match-summary.csv"), StandardCharsets.UTF_8);
        assertTrue(match.contains("matchedFrameCount,1"));
        assertTrue(match.contains("unmatchedFrameCount,1"));

        String catalog = Files.readString(out.resolve("09-signal-catalog.csv"), StandardCharsets.UTF_8);
        assertTrue(catalog.contains("EngineSpeed"));

        String dups = Files.readString(out.resolve("10-duplicate-analysis.txt"), StandardCharsets.UTF_8);
        assertTrue(dups.contains("totalEmittedFrames=2"));

        String mux = Files.readString(out.resolve("07-mux-trace.csv"), StandardCharsets.UTF_8);
        assertTrue(mux.startsWith("frameSequence,") || mux.contains("frameSequence"));
    }

    @Test
    void optionsRejectMissingRequiredPaths() {
        try {
            HarnessOptions.parse(new String[]{"--output", "x"});
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("--mf4"));
        }
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
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            long dt = b.writeDt(concat(records));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private long writeCnChain(SyntheticMdf4Builder b) throws IOException {
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] concat(List<byte[]> records) {
        int n = records.stream().mapToInt(r -> r.length).sum();
        byte[] all = new byte[n];
        int o = 0;
        for (byte[] r : records) {
            System.arraycopy(r, 0, all, o, r.length);
            o += r.length;
        }
        return all;
    }
}