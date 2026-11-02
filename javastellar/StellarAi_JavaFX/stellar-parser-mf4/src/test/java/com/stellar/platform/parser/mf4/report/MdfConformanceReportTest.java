package com.stellar.platform.parser.mf4.report;

import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.mf4.ConformanceMf4Main;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.decode.MdfSchemaDispatch;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfConformanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void auditSyntheticClassicCanFile_populatesRequiredSections() throws Exception {
        Path file = tempDir.resolve("conformance-classic.mf4");
        byte[] record = classicRecord(
                1, FrameDirection.RX, 0x123, 8,
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                2_000_000_000L
        );
        writeClassicCanFile(file, 0, List.of(record), 1);

        MdfConformanceReport report = new MdfConformanceAuditor()
                .audit(file, ReaderContext.ofBatchSize(8));

        assertNotNull(report.identification().mdfVersion());
        assertTrue(report.identification().physicalFileSize() > 0);
        assertNotNull(report.identification().finalizationStatus());
        assertTrue(report.metadata().dgCount() >= 1);
        assertTrue(report.metadata().cgCount() >= 1);
        assertTrue(report.metadata().cnCount() >= 1);
        assertTrue(report.metadata().blockCount() >= 1);
        assertFalse(report.dataGroups().isEmpty());

        MdfConformanceReport.DataGroupSection dg = report.dataGroups().getFirst();
        assertTrue(dg.channelGroupCount() >= 1);
        assertFalse(dg.schemas().isEmpty());
        assertTrue(dg.schemas().stream()
                .anyMatch(s -> s.classification() == MdfSchemaDispatch.Kind.CAN_FRAME));
        assertFalse(dg.segments().isEmpty());

        assertEquals(1, report.canResult().totalCanonicalCanFrames());
        assertEquals(1, report.canResult().uniqueCanIds());
        assertEquals(1, report.canResult().standardFrames());
        assertEquals(0, report.canResult().extendedFrames());
        assertEquals(2_000_000_000L, report.canResult().minTimestampNanos());
        assertEquals(2_000_000_000L, report.canResult().maxTimestampNanos());
        assertEquals(1L, report.canResult().dlcHistogram().get(8));

        assertTrue(report.recordWalk().recordsDecoded() >= 1);
        assertFalse(report.stageNotes().isEmpty());

        String summary = MdfConformanceReportFormatter.formatSummary(report);
        assertTrue(summary.contains("MF4 Conformance Report"));
        assertTrue(summary.contains("CAN frames=1"));

        Map<String, Object> serializable = report.toSerializableMap();
        assertTrue(serializable.containsKey("identification"));
        assertTrue(serializable.containsKey("canResult"));
    }

    @Test
    void resolvePath_prefersCliThenSystemProperty() {
        Path fromArgs = ConformanceMf4Main.resolvePath(new String[]{"C:/tmp/a.mf4"});
        assertEquals(Path.of("C:/tmp/a.mf4"), fromArgs);

        String previous = System.getProperty(ConformanceMf4Main.SYSTEM_PROPERTY);
        try {
            System.setProperty(ConformanceMf4Main.SYSTEM_PROPERTY, "C:/tmp/from-prop.mf4");
            Path fromProp = ConformanceMf4Main.resolvePath(new String[0]);
            assertEquals(Path.of("C:/tmp/from-prop.mf4"), fromProp);
            assertEquals(Path.of("C:/tmp/from-prop.mf4"), ConformanceMf4Main.resolvePath(null));
        } finally {
            if (previous == null) {
                System.clearProperty(ConformanceMf4Main.SYSTEM_PROPERTY);
            } else {
                System.setProperty(ConformanceMf4Main.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void finalizationStatusEnum_exposesBaselineValues() {
        assertNotNull(MdfFinalizationStatus.FINALIZED);
        assertNotNull(MdfFinalizationStatus.UNFINALIZED);
    }

    private static void writeClassicCanFile(
            Path file, int recordIdSize, List<byte[]> records, long cycles
    ) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            byte[] dtPayload;
            if (recordIdSize > 0) {
                List<byte[]> withIds = new ArrayList<>();
                for (byte[] r : records) {
                    withIds.add(prependRecordId((byte) 1, r));
                }
                dtPayload = concat(withIds.toArray(new byte[0][]));
            } else {
                dtPayload = concat(records.toArray(new byte[0][]));
            }
            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, recordIdSize);
            b.patchLink(hd, 0, dg);
        }
    }

    private static long writeClassicCnChain(SyntheticMdf4Builder b) throws IOException {
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] classicRecord(
            int channel,
            FrameDirection direction,
            long canId,
            int dlc,
            byte[] payload,
            long timestampNanos
    ) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) channel);
        buf.put(1, (byte) (direction == FrameDirection.TX ? 1 : 0));
        buf.putInt(2, (int) canId);
        buf.put(6, (byte) dlc);
        byte[] p = payload == null ? new byte[0] : payload;
        for (int i = 0; i < Math.min(8, p.length); i++) {
            data[7 + i] = p[i];
        }
        buf.putLong(15, timestampNanos);
        return data;
    }

    private static byte[] prependRecordId(byte id, byte[] record) {
        byte[] out = new byte[1 + record.length];
        out[0] = id;
        System.arraycopy(record, 0, out, 1, record.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
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