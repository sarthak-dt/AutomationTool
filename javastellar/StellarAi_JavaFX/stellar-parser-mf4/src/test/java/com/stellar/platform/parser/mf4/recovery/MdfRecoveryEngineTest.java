package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
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

class MdfRecoveryEngineTest {

    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    @TempDir
    Path tempDir;

    @Test
    void recoveryNotRequired_forFinalizedFile() throws Exception {
        Path file = tempDir.resolve("final.mf4");
        writeClassic(file, 1, List.of(record(0x10, 100L)), 0);
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, result.frames().size());
        assertEquals(FrameQuality.VALID, result.frames().getFirst().quality());
        assertTrue(result.recoveryResults().isEmpty()
                || result.recoveryResults().stream().allMatch(r -> r.status() == MdfRecoveryStatus.NOT_REQUIRED));
    }

    /**
     * Original POC failure mode: cycleCount=0 + physical records must not silently yield zero frames.
     */
    @Test
    void pocRegression_zeroCyclesWithPhysicalData_recoversExpectedFrames() throws Exception {
        Path file = tempDir.resolve("poc-zero-cycles-recovery.mf4");
        byte[] r1 = record(0x101, 1_000L);
        byte[] r2 = record(0x202, 2_000L);
        byte[] r3 = record(0x303, 3_000L);
        writeClassic(file, 0, List.of(r1, r2, r3), 0); // cycleCount = 0

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(2));
        assertTrue(result.schemaResult().recordSchemas().stream()
                .anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE));
        assertFalse(result.frames().isEmpty(), "must not silently return zero frames");
        assertEquals(3, result.frames().size());
        assertEquals(0x101L, result.frames().get(0).canId());
        assertEquals(0x202L, result.frames().get(1).canId());
        assertEquals(0x303L, result.frames().get(2).canId());
        assertEquals(1_000L, result.frames().get(0).timestampNanos());
        assertEquals(2_000L, result.frames().get(1).timestampNanos());
        assertEquals(3_000L, result.frames().get(2).timestampNanos());
        assertEquals(1, result.frames().get(0).data()[0] & 0xFF);
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
        assertTrue(result.frames().stream().allMatch(f -> f.source().sourceByteOffset() >= 0));
        assertTrue(result.recoveryResults().stream()
                .anyMatch(r -> r.status() == MdfRecoveryStatus.RECOVERED
                        || r.status() == MdfRecoveryStatus.PARTIALLY_RECOVERED));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.reason().contains("ZERO_DECLARED_CYCLES")
                        || d.code().name().contains("RECOVERY")));
    }

    @Test
    void recoveredFramesHaveUniquePhysicalOffsets_noDuplicateEmission() throws Exception {
        Path file = tempDir.resolve("unique-offsets.mf4");
        writeClassic(file, 0, List.of(
                record(0x101, 1_000L),
                record(0x202, 2_000L),
                record(0x303, 3_000L),
                record(0x404, 4_000L)
        ), 0);
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(2));
        assertEquals(4, result.frames().size());
        long distinctOffsets = result.frames().stream()
                .map(f -> f.source().sourceByteOffset())
                .distinct()
                .count();
        assertEquals(result.frames().size(), distinctOffsets, "each physical record must emit once");
        long distinctSequences = result.frames().stream()
                .map(CanonicalCanFrame::sequenceNumber)
                .distinct()
                .count();
        assertEquals(result.frames().size(), distinctSequences);
    }

    @Test
    void zeroDeclaredPayload_withTrailingPhysicalBytes() throws Exception {
        Path file = tempDir.resolve("zero-decl.mf4");
        byte[] r1 = record(0x11, 10L);
        byte[] r2 = record(0x22, 20L);
        long dtAddress;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1); // unfinalized flag
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            dtAddress = b.writeDt(concat(r1, r2));
            // Claim header-only block (no declared data) while bytes remain after blockEnd
            b.patchDeclaredLength(dtAddress, 24);
            long dg = b.writeDg(0, cg, dtAddress, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.reason().contains("EFFECTIVE_BOUNDARY_EXTENDED")
                        || d.reason().contains("ZERO_DECLARED")));
    }

    @Test
    void declaredShorterThanActualRecords_extendsBoundary() throws Exception {
        Path file = tempDir.resolve("short-decl.mf4");
        byte[] r1 = record(0x1, 1L);
        byte[] r2 = record(0x2, 2L);
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            long dt = b.writeDt(concat(r1, r2));
            // Shrink declared length so only first record is "declared"
            b.patchDeclaredLength(dt, 24L + 23L);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        assertEquals(2, decoder.decodeAll(file, ReaderContext.ofBatchSize(4)).frames().size());
    }

    @Test
    void multipleCgRecordIds_recovered() throws Exception {
        Path file = tempDir.resolve("multi-cg-rec.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long p2 = b.writeCn(0, b.writeTx("DataBytes"), 0, 0, 0, 9, 3, 0, 8, 0);
            long d2 = b.writeCn(p2, b.writeTx("DLC"), 0, 0, 0, 0, 2, 0, 8, 0);
            long i2 = b.writeCn(d2, b.writeTx("CAN_ID"), 0, 0, 0, 0, 0, 0, 16, 0);
            long cg2 = b.writeCg(0, i2, 2, 0, 0, 4, 0);
            long head1 = writeCnChain(b);
            long cg1 = b.writeCg(cg2, head1, 1, 0, 0, 23, 0);
            byte[] a = prepend((byte) 1, record(0x10, 5L));
            byte[] c = new byte[]{2, 0x34, 0x12, 1, (byte) 0xAB};
            long dt = b.writeDt(concat(a, c));
            long dg = b.writeDg(0, cg1, dt, 1);
            b.patchLink(hd, 0, dg);
        }
        List<CanonicalCanFrame> frames = decoder.decodeAll(file, ReaderContext.ofBatchSize(8)).frames();
        assertEquals(2, frames.size());
        assertTrue(frames.stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
    }

    @Test
    void sharedDg_recoveryDoesNotDoubleEmitNormalCg() throws Exception {
        Path file = tempDir.resolve("shared-dg-no-overlap.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);

            long headOk = writeCnChain(b);
            long cgOk = b.writeCg(0, headOk, 2, 1, 0, 23, 0); // finalized / normal-owned
            long headRec = writeCnChain(b);
            long cgRec = b.writeCg(cgOk, headRec, 1, 0, 0, 23, 0); // recovery

            byte[] recovered = prepend((byte) 1, record(0x111, 10L));
            byte[] normal = prepend((byte) 2, record(0x222, 20L));
            long dt = b.writeDt(concat(recovered, normal));
            long dg = b.writeDg(0, cgRec, dt, 1);
            b.patchLink(hd, 0, dg);
        }

        List<CanonicalCanFrame> frames = decoder.decodeAll(file, ReaderContext.ofBatchSize(8)).frames();
        assertEquals(2, frames.size(), "must not double-decode shared DT records");
        long recoveredCount = frames.stream().filter(f -> f.quality() == FrameQuality.RECOVERED).count();
        long validCount = frames.stream().filter(f -> f.quality() == FrameQuality.VALID).count();
        assertEquals(1, recoveredCount);
        assertEquals(1, validCount);
        assertTrue(frames.stream().anyMatch(f -> f.quality() == FrameQuality.RECOVERED && f.canId() == 0x111L));
        assertTrue(frames.stream().anyMatch(f -> f.quality() == FrameQuality.VALID && f.canId() == 0x222L));
        assertEquals(2, frames.stream().map(CanonicalCanFrame::sequenceNumber).distinct().count());
    }

    @Test
    void multipleDgs_mixedValidAndRecovery() throws Exception {
        Path file = tempDir.resolve("mixed-dg.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);

            long headRec = writeCnChain(b);
            long cgRec = b.writeCg(0, headRec, 1, 0, 0, 23, 0); // recovery
            long dtRec = b.writeDt(record(0x222, 20L));
            long dgRec = b.writeDg(0, cgRec, dtRec, 0);

            long headOk = writeCnChain(b);
            long cgOk = b.writeCg(0, headOk, 1, 1, 0, 23, 0); // finalized
            long dtOk = b.writeDt(record(0x111, 10L));
            long dgOk = b.writeDg(dgRec, cgOk, dtOk, 0);
            b.patchLink(hd, 0, dgOk);
        }
        List<CanonicalCanFrame> frames = decoder.decodeAll(file, ReaderContext.ofBatchSize(8)).frames();
        assertEquals(2, frames.size());
        assertTrue(frames.stream().anyMatch(f -> f.quality() == FrameQuality.VALID && f.canId() == 0x111L));
        assertTrue(frames.stream().anyMatch(f -> f.quality() == FrameQuality.RECOVERED && f.canId() == 0x222L));
        assertEquals(0L, frames.get(0).sequenceNumber());
        assertEquals(1L, frames.get(1).sequenceNumber());
    }

    @Test
    void truncatedFinalRecord_recoversValidPrefix() throws Exception {
        Path file = tempDir.resolve("trunc-rec.mf4");
        byte[] full = record(0x3, 1L);
        byte[] partial = new byte[]{1, 2, 3};
        writeClassic(file, 0, List.of(full, partial), 0);
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, result.frames().size());
        assertTrue(result.recoveryResults().stream()
                .anyMatch(r -> r.status() == MdfRecoveryStatus.RECOVERED
                        || r.status() == MdfRecoveryStatus.PARTIALLY_RECOVERED));
        assertTrue(result.recoveryResults().stream()
                .flatMap(r -> r.diagnostics().stream())
                .anyMatch(d -> d.code() == MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD
                        || d.code() == MdfRecoveryDiagnosticCode.PARTIAL_RECOVERY
                        || d.code() == MdfRecoveryDiagnosticCode.RECOVERY_COMPLETED));
    }

    @Test
    void unknownRecordId_stopsSafely() throws Exception {
        Path file = tempDir.resolve("unk-rid.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            byte[] good = prepend((byte) 1, record(0x1, 1L));
            byte[] bad = prepend((byte) 9, record(0x2, 2L));
            long dt = b.writeDt(concat(good, bad));
            long dg = b.writeDg(0, cg, dt, 1);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, result.frames().size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.reason().contains("UNKNOWN_RECORD_ID")
                || d.code().name().contains("UNKNOWN")));
    }

    @Test
    void cancellation_duringRecovery() throws Exception {
        Path file = tempDir.resolve("cancel-rec.mf4");
        writeClassic(file, 0, List.of(record(1, 1L), record(2, 2L), record(3, 3L)), 0);
        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        MdfDynamicRecordDecoder.DecodeResult result =
                decoder.decodeAll(file, new ReaderContext(2, token));
        assertTrue(result.frames().isEmpty()
                || result.diagnostics().stream().anyMatch(d -> d.code().name().contains("CANCEL")));
    }

    @Test
    void batching_duringRecovery() throws Exception {
        Path file = tempDir.resolve("batch-rec.mf4");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(record(0x100 + i, i));
        }
        writeClassic(file, 0, records, 0);
        AtomicInteger batches = new AtomicInteger();
        List<CanonicalCanFrame> frames = new ArrayList<>();
        new com.stellar.platform.parser.mf4.Mf4FileReader().readFrames(
                file,
                ReaderContext.ofBatchSize(2),
                batch -> {
                    batches.incrementAndGet();
                    frames.addAll(batch);
                },
                w -> { }
        );
        assertEquals(5, frames.size());
        assertEquals(3, batches.get());
        assertTrue(frames.stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
    }

    @Test
    void falsePositive_randomTrailingBytes_notFabricatedIntoFrames() throws Exception {
        Path file = tempDir.resolve("false-pos.mf4");
        byte[] r1 = record(0x55, 55L);
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            byte[] garbage = new byte[64];
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) (0xA5 ^ i);
            }
            long dt = b.writeDt(concat(r1, garbage));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        // Only the structurally valid first record — garbage must not become CAN frames
        assertEquals(1, result.frames().size());
        assertEquals(0x55L, result.frames().getFirst().canId());
        assertTrue(result.frames().getFirst().quality() == FrameQuality.RECOVERED);
        assertTrue(result.recoveryResults().stream()
                .anyMatch(r -> r.status() == MdfRecoveryStatus.PARTIALLY_RECOVERED
                        || r.recordsRejected() > 0
                        || r.diagnostics().stream().anyMatch(d ->
                        d.code() == MdfRecoveryDiagnosticCode.INVALID_RECOVERY_CANDIDATE
                                || d.code() == MdfRecoveryDiagnosticCode.PARTIAL_RECOVERY
                                || d.code() == MdfRecoveryDiagnosticCode.RESYNC_FAILED)));
    }

    @Test
    void flowA_finalized_and_flowB_unfinalized_integration() throws Exception {
        Path finalized = tempDir.resolve("flow-a.mf4");
        writeClassic(finalized, 2, List.of(record(0x123, 9L), record(0x124, 10L)), 0);
        MdfDynamicRecordDecoder.DecodeResult a = decoder.decodeAll(finalized, ReaderContext.ofBatchSize(8));
        assertEquals(2, a.frames().size());
        assertTrue(a.frames().stream().allMatch(f -> f.quality() == FrameQuality.VALID));

        Path unfinalized = tempDir.resolve("flow-b.mf4");
        writeClassic(unfinalized, 0, List.of(record(0x123, 9L), record(0x124, 10L)), 1);
        MdfDynamicRecordDecoder.DecodeResult b = decoder.decodeAll(unfinalized, ReaderContext.ofBatchSize(8));
        assertTrue(b.schemaResult().recordSchemas().stream()
                .anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE));
        assertEquals(2, b.frames().size());
        assertTrue(b.frames().stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
        assertEquals(0x123, b.frames().get(0).canId());
        assertEquals(0x124, b.frames().get(1).canId());
    }

    // --- helpers ---

    private void writeClassic(Path file, long cycles, List<byte[]> records, int unfin) throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            long dt = b.writeDt(concat(records.toArray(new byte[0][])));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static long writeCnChain(SyntheticMdf4Builder b) throws IOException {
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] record(long canId, long timestampNanos) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
        buf.put(6, (byte) 1);
        data[7] = 1;
        buf.putLong(15, timestampNanos);
        return data;
    }

    private static byte[] prepend(byte id, byte[] record) {
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
