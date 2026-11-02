package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfDynamicRecordDecoderTest {

    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    @TempDir
    Path tempDir;

    @Test
    void endToEnd_singleDgCgRecord_throughDiscoveryAndSchemaBuilder() throws Exception {
        Path file = tempDir.resolve("e2e.mf4");
        byte[] record = classicRecord(
                1, FrameDirection.RX, 0x123, false, 8,
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                1_000_000_000L
        );
        writeClassicCanFile(file, 0, List.of(record), 1, false);

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(16));
        assertEquals(1, result.frames().size());
        CanonicalCanFrame frame = result.frames().getFirst();
        assertEquals(0L, frame.sequenceNumber());
        assertEquals(1_000_000_000L, frame.timestampNanos());
        assertEquals(0x123L, frame.canId());
        assertFalse(frame.extended());
        assertEquals(1, frame.channel());
        assertEquals(FrameDirection.RX, frame.direction());
        assertEquals(8, frame.dlc());
        assertEquals(8, frame.data().length);
        assertEquals(1, frame.data()[0]);
        assertEquals(8, frame.data()[7]);
        assertEquals(FrameQuality.VALID, frame.quality());
        assertTrue(frame.source().sourceByteOffset() >= 0);
    }

    @Test
    void multipleRecordsAndBatchBoundary() throws Exception {
        Path file = tempDir.resolve("multi.mf4");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(classicRecord(
                    0, FrameDirection.TX, 0x100 + i, false, 2,
                    new byte[]{(byte) i, (byte) (i + 1)},
                    1000L * i
            ));
        }
        writeClassicCanFile(file, 0, records, 5, false);

        AtomicInteger batches = new AtomicInteger();
        List<CanonicalCanFrame> frames = new ArrayList<>();
        new Mf4FileReaderAdapter().read(file, ReaderContext.ofBatchSize(2), batch -> {
            batches.incrementAndGet();
            frames.addAll(batch);
        });
        assertEquals(5, frames.size());
        assertEquals(3, batches.get()); // 2+2+1
        assertEquals(0L, frames.get(0).sequenceNumber());
        assertEquals(4L, frames.get(4).sequenceNumber());
        assertEquals(FrameDirection.TX, frames.get(0).direction());
    }

    @Test
    void multipleCgsWithRecordIdsAndDifferentSizes() throws Exception {
        Path file = tempDir.resolve("multi-cg.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);

            // CG2 recordId=2, dataBytes=4 (id u16 + dlc u8 + payload 1)
            long p2 = b.writeCn(0, b.writeTx("DataBytes"), 0, 0, 0, 9, 3, 0, 8, 0);
            long d2 = b.writeCn(p2, b.writeTx("DLC"), 0, 0, 0, 0, 2, 0, 8, 0);
            long i2 = b.writeCn(d2, b.writeTx("CAN_ID"), 0, 0, 0, 0, 0, 0, 16, 0);
            long cg2 = b.writeCg(0, i2, 2, 1, 0, 4, 0);

            // CG1 recordId=1, classic 23-byte layout
            long cg1Head = writeClassicCnChain(b);
            long cg1 = b.writeCg(cg2, cg1Head, 1, 1, 0, 23, 0);

            byte[] r1 = prependRecordId((byte) 1, classicRecord(
                    0, FrameDirection.RX, 0x10, false, 8, new byte[8], 5L
            ));
            byte[] r2 = new byte[]{2, 0x34, 0x12, 1, (byte) 0xAB}; // id=2, can=0x1234, dlc=1, data=0xAB
            long dt = b.writeDt(concat(r1, r2));
            long dg = b.writeDg(0, cg1, dt, 1);
            b.patchLink(hd, 0, dg);
        }

        List<CanonicalCanFrame> frames = decoder.decodeAll(file, ReaderContext.ofBatchSize(8)).frames();
        assertEquals(2, frames.size());
        assertTrue(frames.stream().anyMatch(f -> f.canId() == 0x10L));
        assertTrue(frames.stream().anyMatch(f -> f.canId() == 0x1234L && f.dlc() == 1));
    }

    @Test
    void multipleDataGroups() throws Exception {
        Path file = tempDir.resolve("multi-dg.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);

            long head2 = writeClassicCnChain(b);
            long cg2 = b.writeCg(0, head2, 1, 1, 0, 23, 0);
            byte[] rec2 = classicRecord(2, FrameDirection.RX, 0x222, false, 1, new byte[]{9}, 20L);
            long dt2 = b.writeDt(rec2);
            long dg2 = b.writeDg(0, cg2, dt2, 0);

            long head1 = writeClassicCnChain(b);
            long cg1 = b.writeCg(0, head1, 1, 1, 0, 23, 0);
            byte[] rec1 = classicRecord(1, FrameDirection.TX, 0x111, false, 1, new byte[]{8}, 10L);
            long dt1 = b.writeDt(rec1);
            long dg1 = b.writeDg(dg2, cg1, dt1, 0);
            b.patchLink(hd, 0, dg1);
        }
        List<CanonicalCanFrame> frames = decoder.decodeAll(file, ReaderContext.ofBatchSize(8)).frames();
        assertEquals(2, frames.size());
        assertEquals(0L, frames.get(0).sequenceNumber());
        assertEquals(1L, frames.get(1).sequenceNumber());
    }

    @Test
    void standardAndExtendedCanId_rxTx_classicAndFd() throws Exception {
        Path std = tempDir.resolve("std.mf4");
        writeClassicCanFile(std, 0, List.of(classicRecord(
                0, FrameDirection.RX, 0x7FF, false, 8, new byte[8], 1L
        )), 1, false);
        CanonicalCanFrame f1 = decoder.decodeAll(std, ReaderContext.ofBatchSize(4)).frames().getFirst();
        assertFalse(f1.extended());
        assertEquals(0x7FFL, f1.canId());
        assertEquals(FrameDirection.RX, f1.direction());

        Path ext = tempDir.resolve("ext.mf4");
        writeClassicCanFile(ext, 0, List.of(classicRecord(
                0, FrameDirection.TX, 0x18DAF110L, true, 8, new byte[8], 2L
        )), 1, true);
        CanonicalCanFrame f2 = decoder.decodeAll(ext, ReaderContext.ofBatchSize(4)).frames().getFirst();
        assertTrue(f2.extended());
        assertEquals(0x18DAF110L, f2.canId());
        assertEquals(FrameDirection.TX, f2.direction());

        Path fd = tempDir.resolve("fd.mf4");
        byte[] payload = new byte[64];
        payload[63] = 0x55;
        writeFdCanFile(fd, payload, 64, 3L);
        CanonicalCanFrame f3 = decoder.decodeAll(fd, ReaderContext.ofBatchSize(4)).frames().getFirst();
        assertEquals(64, f3.dlc());
        assertEquals(64, f3.data().length);
        assertEquals(0x55, f3.data()[63] & 0xFF);
    }

    @Test
    void timestampNotAtFirstByte_andFloatTimestamp() throws Exception {
        Path file = tempDir.resolve("ts.mf4");
        writeClassicCanFile(file, 0, List.of(classicRecord(
                3, FrameDirection.RX, 0x55, false, 0, new byte[0], 9_000_000_000L
        )), 1, false);
        assertEquals(9_000_000_000L,
                decoder.decodeAll(file, ReaderContext.ofBatchSize(2)).frames().getFirst().timestampNanos());

        Path floatFile = tempDir.resolve("ts-float.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(floatFile)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            // layout: can_id u32 @0, float seconds @4, dlc @8, payload 1 @9  => 10 bytes
            long payload = b.writeCn(0, b.writeTx("DataBytes"), 0, 0, 0, 9, 9, 0, 8, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 8, 0, 8, 0);
            long ts = b.writeCn(dlc, b.writeTx("t_stamp"), 0, 2, 1, 4, 4, 0, 32, 0); // FLOAT_LE
            long id = b.writeCn(ts, b.writeTx("can_id"), 0, 0, 0, 0, 0, 0, 32, 0);
            long cg = b.writeCg(0, id, 1, 1, 0, 10, 0);
            ByteBuffer rec = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
            rec.putInt(0x42);
            rec.putFloat(2.0f);
            rec.put((byte) 1);
            rec.put((byte) 0x99);
            long dt = b.writeDt(rec.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        CanonicalCanFrame frame = decoder.decodeAll(floatFile, ReaderContext.ofBatchSize(2)).frames().getFirst();
        assertEquals(2_000_000_000L, frame.timestampNanos());
        assertEquals(0x42L, frame.canId());
    }

    @Test
    void invalidationBitSkipsInvalidCanId() throws Exception {
        Path file = tempDir.resolve("inval.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            int invalFlag = 0x02;
            long payload = b.writeCn(0, b.writeTx("DataBytes"), 0, 0, 0, 9, 5, 0, 8, 0, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 4, 0, 8, 0, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 0, 0, 32, 0, invalFlag);
            long cg = b.writeCg(0, id, 1, 1, 0, 6, 1);
            // data: can=0x11, dlc=1, payload=0xAA ; inval byte bit0 set
            byte[] rec = new byte[]{0x11, 0, 0, 0, 1, (byte) 0xAA, 0x01};
            long dt = b.writeDt(rec);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(result.frames().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfDecodeDiagnosticCode.INVALIDATION_MARKED_DATA));
    }

    @Test
    void unknownRecordId_truncatedLastRecord_cancellation() throws Exception {
        Path unknown = tempDir.resolve("unknown-rid.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(unknown)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, 2, 0, 23, 0);
            byte[] good = prependRecordId((byte) 1, classicRecord(
                    0, FrameDirection.RX, 0x1, false, 0, new byte[0], 1L
            ));
            byte[] bad = prependRecordId((byte) 9, classicRecord(
                    0, FrameDirection.RX, 0x2, false, 0, new byte[0], 2L
            ));
            long dt = b.writeDt(concat(good, bad));
            long dg = b.writeDg(0, cg, dt, 1);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult unknownResult =
                decoder.decodeAll(unknown, ReaderContext.ofBatchSize(8));
        assertEquals(1, unknownResult.frames().size());
        assertTrue(unknownResult.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID));

        Path trunc = tempDir.resolve("trunc.mf4");
        byte[] full = classicRecord(0, FrameDirection.RX, 0x3, false, 2, new byte[]{1, 2}, 1L);
        byte[] partial = new byte[]{1, 2, 3}; // truncated
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(trunc)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, 2, 0, 23, 0);
            long dt = b.writeDt(concat(full, partial));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult truncResult =
                decoder.decodeAll(trunc, ReaderContext.ofBatchSize(8));
        assertEquals(1, truncResult.frames().size());
        assertTrue(truncResult.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfDecodeDiagnosticCode.TRUNCATED_RECORD));

        Path cancelFile = tempDir.resolve("cancel.mf4");
        writeClassicCanFile(cancelFile, 0, List.of(
                classicRecord(0, FrameDirection.RX, 1, false, 0, new byte[0], 1L),
                classicRecord(0, FrameDirection.RX, 2, false, 0, new byte[0], 2L)
        ), 2, false);
        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        MdfDynamicRecordDecoder.DecodeResult cancelled =
                decoder.decodeAll(cancelFile, new ReaderContext(8, token));
        assertTrue(cancelled.frames().isEmpty()
                || cancelled.diagnostics().stream().anyMatch(d -> d.code() == MdfDecodeDiagnosticCode.CANCELLED));
    }

    @Test
    void dtAndOrderedDlChain() throws Exception {
        Path dtFile = tempDir.resolve("dt.mf4");
        writeClassicCanFile(dtFile, 0, List.of(classicRecord(
                0, FrameDirection.RX, 0x11, false, 1, new byte[]{7}, 1L
        )), 1, false);
        assertEquals(1, decoder.decodeAll(dtFile, ReaderContext.ofBatchSize(4)).frames().size());

        Path dlFile = tempDir.resolve("dl.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(dlFile)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, 2, 0, 23, 0);
            byte[] r1 = classicRecord(0, FrameDirection.RX, 0x1, false, 0, new byte[0], 1L);
            byte[] r2 = classicRecord(0, FrameDirection.RX, 0x2, false, 0, new byte[0], 2L);
            long dt1 = b.writeDt(r1);
            long dt2 = b.writeDt(r2);
            long dl = b.writeDl(0, new long[]{dt1, dt2}, 0);
            long dg = b.writeDg(0, cg, dl, 0);
            b.patchLink(hd, 0, dg);
        }
        assertEquals(2, decoder.decodeAll(dlFile, ReaderContext.ofBatchSize(8)).frames().size());
    }

    @Test
    void recoveryCandidateSchema_reportsRecoveryRequired_notSilentZero() throws Exception {
        Path file = tempDir.resolve("recovery.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0); // cycleCount = 0
            byte[] payload = classicRecord(0, FrameDirection.RX, 0x5, false, 1, new byte[]{1}, 1L);
            // two physical records worth of bytes
            long dt = b.writeDt(concat(payload, payload));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertTrue(result.schemaResult().recordSchemas().stream()
                .anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfDecodeDiagnosticCode.RECOVERY_REQUIRED));
        // Recovery Engine must recover physical records — never silent zero
        assertEquals(2, result.frames().size());
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
    }

    @Test
    void dzUnsupportedDiagnostic() throws Exception {
        Path file = tempDir.resolve("dz.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b);
            long cg = b.writeCg(0, head, 1, 1, 0, 23, 0);
            long dz = b.writeDz("DT", 1, 23, new byte[]{1, 2, 3});
            long dg = b.writeDg(0, cg, dz, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().name().startsWith("DZ_")
                        || d.code() == MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE));
    }

    // --- helpers ---

    private static byte[] classicRecord(
            int channel,
            FrameDirection direction,
            long canId,
            boolean extended,
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
        // extended flag is encoded via CN bitLength in file metadata, not record bytes
        return data;
    }

    private void writeClassicCanFile(
            Path file, int recordIdSize, List<byte[]> records, long cycles, boolean extendedId
    ) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeClassicCnChain(b, extendedId);
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
            // If last element shorter than 23, allow (truncation tests write separately)
            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, recordIdSize);
            b.patchLink(hd, 0, dg);
        }
    }

    private void writeFdCanFile(Path file, byte[] payload, int dlc, long timestampNanos) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            // id@0(4), fd@4(1), dlc@5(1), payload@6(64), time@70(8) => 78 bytes
            int dataBytes = 78;
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 70, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 6, 0, 512, 0);
            long dlcCn = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 5, 0, 8, 0);
            long fd = b.writeCn(dlcCn, b.writeTx("CAN_FD"), 0, 0, 0, 0, 4, 0, 8, 0);
            long id = b.writeCn(fd, b.writeTx("CAN_ID"), 0, 0, 0, 0, 0, 0, 32, 0);
            long cg = b.writeCg(0, id, 1, 1, 0, dataBytes, 0);
            ByteBuffer buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(0x100);
            buf.put((byte) 1); // can fd
            buf.put((byte) dlc);
            byte[] p = new byte[64];
            System.arraycopy(payload, 0, p, 0, Math.min(64, payload.length));
            buf.put(p);
            buf.putLong(timestampNanos);
            long dt = b.writeDt(buf.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static long writeClassicCnChain(SyntheticMdf4Builder b) throws IOException {
        return writeClassicCnChain(b, false);
    }

    private static long writeClassicCnChain(SyntheticMdf4Builder b, boolean extendedId) throws IOException {
        // Offsets: ch@0, dir@1, id@2, dlc@6, payload@7, time@15 ; dataBytes=23
        int idBits = extendedId ? 29 : 11;
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, idBits, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
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

    /** Thin adapter so batch test uses AutomotiveFileReader path. */
    private final class Mf4FileReaderAdapter {
        void read(Path file, ReaderContext ctx, java.util.function.Consumer<List<CanonicalCanFrame>> onBatch)
                throws Exception {
            List<ProcessingWarning> warnings = new ArrayList<>();
            new com.stellar.platform.parser.mf4.Mf4FileReader().readFrames(
                    file, ctx, onBatch::accept, warnings::add
            );
        }
    }
}
