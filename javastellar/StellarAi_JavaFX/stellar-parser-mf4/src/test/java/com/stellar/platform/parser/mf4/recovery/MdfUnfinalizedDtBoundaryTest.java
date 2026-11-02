package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.block.MdfBlockCandidateValidator;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryRequest;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryResolution;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfUnfinalizedDtBoundaryResolver;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite for unfinalized / incomplete DT physical-boundary resolution.
 * <p>
 * Proves raw {@code ##} inside measurement payload is not a block boundary, while a
 * structurally valid following MDF block still truncates the recovery region.
 */
class MdfUnfinalizedDtBoundaryTest {

    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    @TempDir
    Path tempDir;

    @Test
    void falseHashHashInsidePayload_doesNotStopRecovery() throws Exception {
        Path file = tempDir.resolve("false-hashhash.mf4");
        byte[] before = record(0x101, 1_000L);
        byte[] withMarker = recordWithPayloadBytes(0x202, 2_000L, new byte[]{
                '#', '#', 0x11, 0x22, 0x33, 0x44, 0x55, 0x66
        });
        byte[] after1 = record(0x303, 3_000L);
        byte[] after2 = record(0x404, 4_000L);

        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            long dt = b.writeDt(concat(before, withMarker, after1, after2));
            b.patchDeclaredLength(dt, 24);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(4, result.frames().size(), "must recover records after false ## in payload");
        assertEquals(0x101L, result.frames().get(0).canId());
        assertEquals(0x202L, result.frames().get(1).canId());
        assertEquals(0x303L, result.frames().get(2).canId());
        assertEquals(0x404L, result.frames().get(3).canId());
        assertEquals(4, distinctOffsets(result.frames()).size(), "no duplicate records");

        assertTrue(result.recoveryResults().stream()
                        .flatMap(r -> r.diagnostics().stream())
                        .anyMatch(d -> d.code() == MdfRecoveryDiagnosticCode.FALSE_MDF_BLOCK_MARKER_IGNORED
                                || d.reason().contains("FALSE_MDF_BLOCK_MARKER")),
                "false ## candidate must be reported in aggregate");

        // Direct proof: the payload ## offset is not a valid MDF block.
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            long dtAddr = findDtAddress(access);
            MdfBlockHeader dtHeader = MdfBlockHeaderReader.read(access, dtAddr);
            long markerAt = dtHeader.blockEnd() + before.length + 7; // DataBytes offset in record
            assertEquals('#', access.readFully(markerAt, 1, "m0")[0]);
            assertEquals('#', access.readFully(markerAt + 1, 1, "m1")[0]);
            assertTrue(MdfBlockCandidateValidator.validateBlockCandidate(access, markerAt).isEmpty());
        }
    }

    @Test
    void structurallyValidNextBlock_stopsBeforeBlockBytes() throws Exception {
        Path file = tempDir.resolve("valid-next-block.mf4");
        byte[] r1 = record(0x11, 10L);
        byte[] r2 = record(0x22, 20L);
        long txAddress;
        long dtAddress;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            dtAddress = b.writeDt(concat(r1, r2));
            b.patchDeclaredLength(dtAddress, 24);
            txAddress = b.writeTx("BOUNDARY");
            long dg = b.writeDg(0, cg, dtAddress, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size());
        assertEquals(0x11L, result.frames().get(0).canId());
        assertEquals(0x22L, result.frames().get(1).canId());

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfBlockHeader dtHeader = MdfBlockHeaderReader.read(access, dtAddress);
            MdfDtBoundaryResolution resolution = MdfUnfinalizedDtBoundaryResolver.resolveUnfinalizedDtEnd(
                    access,
                    dtHeader.blockEnd(),
                    true,
                    MdfDtBoundaryRequest.forRecovery(true, null)
            );
            assertEquals(txAddress, resolution.endExclusive(), "recovery end must be next valid block");
            assertEquals(txAddress, resolution.acceptedBoundaryOffset());
            assertFalse(resolution.usedEofFallback());

            // TX payload must not be consumed as a CAN record (no fabricated frames from "BOUNDARY")
            Optional<MdfBlockHeader> tx = MdfBlockCandidateValidator.validateBlockCandidate(access, txAddress);
            assertTrue(tx.isPresent());
            assertTrue(result.frames().stream().noneMatch(f -> f.source().sourceByteOffset() >= txAddress));
        }
    }

    @Test
    void noValidFollowingBlock_unfinalized_extendsToEof() throws Exception {
        Path file = tempDir.resolve("eof-fallback.mf4");
        byte[] r1 = record(0xA1, 100L);
        byte[] r2 = record(0xA2, 200L);
        byte[] r3 = record(0xA3, 300L);
        long dtAddress;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            // DG before DT so orphaned payload has no following MDF block
            long dg = b.writeDg(0, cg, 0, 0);
            b.patchLink(hd, 0, dg);
            dtAddress = b.writeDt(new byte[0]);
            b.patchLink(dg, 2, dtAddress);
            b.appendRaw(concat(r1, r2, r3));
        }

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(3, result.frames().size());
        assertEquals(0xA3L, result.frames().get(2).canId());

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfBlockHeader dtHeader = MdfBlockHeaderReader.read(access, dtAddress);
            MdfDtBoundaryResolution resolution = MdfUnfinalizedDtBoundaryResolver.resolveUnfinalizedDtEnd(
                    access,
                    dtHeader.blockEnd(),
                    true,
                    MdfDtBoundaryRequest.forRecovery(true, null)
            );
            assertEquals(access.fileSize(), resolution.endExclusive());
            assertTrue(resolution.usedEofFallback());
            assertEquals(-1L, resolution.acceptedBoundaryOffset());
        }
    }

    @Test
    void truncatedFinalRecord_emitsPrefixAndTruncationDiagnostic() throws Exception {
        Path file = tempDir.resolve("trunc-tail.mf4");
        byte[] full1 = record(0x31, 1L);
        byte[] full2 = record(0x32, 2L);
        byte[] partial = new byte[]{0x01, 0x02, 0x03, 0x04};
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            long dg = b.writeDg(0, cg, 0, 0);
            b.patchLink(hd, 0, dg);
            long dt = b.writeDt(new byte[0]);
            b.patchLink(dg, 2, dt);
            b.appendRaw(concat(full1, full2, partial));
        }

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size(), "incomplete final record must not be fabricated");
        assertEquals(0x31L, result.frames().get(0).canId());
        assertEquals(0x32L, result.frames().get(1).canId());
        assertTrue(result.recoveryResults().stream()
                .flatMap(r -> r.diagnostics().stream())
                .anyMatch(d -> d.code() == MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD
                        || d.code() == MdfRecoveryDiagnosticCode.PARTIAL_RECOVERY));
    }

    @Test
    void finalizedDeclaredDtLength_unchangedByRecoveryBoundaryLogic() throws Exception {
        Path file = tempDir.resolve("finalized-declared.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 2, 0, 23, 0);
            long dt = b.writeDt(concat(record(0x10, 10L), record(0x20, 20L)));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size());
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.VALID));
        assertTrue(result.recoveryResults().isEmpty()
                || result.recoveryResults().stream().allMatch(r -> r.status() == MdfRecoveryStatus.NOT_REQUIRED));
        assertTrue(result.recoveryResults().stream()
                .flatMap(r -> r.diagnostics().stream())
                .noneMatch(d -> d.code() == MdfRecoveryDiagnosticCode.EXTENDED_UNFINALIZED_DT_RECOVERY));
    }

    @Test
    void finalizedShortDeclared_withoutUnfin_doesNotEofWhenNoNextBlock() throws Exception {
        Path file = tempDir.resolve("finalized-no-eof.mf4");
        long dtAddress;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, 0, 0, 23, 0);
            long dg = b.writeDg(0, cg, 0, 0);
            b.patchLink(hd, 0, dg);
            // Non-zero but short declared length: one record declared, extra bytes appended, no next block
            dtAddress = b.writeDt(record(0x55, 55L));
            b.patchLink(dg, 2, dtAddress);
            b.patchDeclaredLength(dtAddress, 24L + 23L);
            b.appendRaw(record(0x66, 66L));
        }

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfBlockHeader dtHeader = MdfBlockHeaderReader.read(access, dtAddress);
            MdfDtBoundaryResolution resolution = MdfUnfinalizedDtBoundaryResolver.resolveUnfinalizedDtEnd(
                    access,
                    dtHeader.blockEnd(),
                    false,
                    MdfDtBoundaryRequest.forRecovery(false, null)
            );
            assertEquals(dtHeader.blockEnd(), resolution.endExclusive(),
                    "finalized files must not silently extend short DT to EOF");
            assertFalse(resolution.usedEofFallback());
        }
    }

    private static long findDtAddress(MdfFileAccess access) throws Exception {
        // Scan for first ##DT (test files are tiny).
        for (long p = 64; p + 4 < access.fileSize(); p += 8) {
            byte[] id = access.readFully(p, 4, "id");
            if (id[0] == '#' && id[1] == '#' && id[2] == 'D' && id[3] == 'T') {
                return p;
            }
        }
        throw new AssertionError("##DT not found");
    }

    private static Set<Long> distinctOffsets(List<CanonicalCanFrame> frames) {
        Set<Long> offsets = new HashSet<>();
        for (CanonicalCanFrame f : frames) {
            offsets.add(f.source().sourceByteOffset());
        }
        return offsets;
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
        return recordWithPayloadBytes(canId, timestampNanos, new byte[]{1, 0, 0, 0, 0, 0, 0, 0});
    }

    private static byte[] recordWithPayloadBytes(long canId, long timestampNanos, byte[] payload8) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
        buf.put(6, (byte) 8);
        System.arraycopy(payload8, 0, data, 7, Math.min(8, payload8.length));
        buf.putLong(15, timestampNanos);
        return data;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
