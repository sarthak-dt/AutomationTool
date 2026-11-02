package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceSchema;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfDzDecompressionTest {

    @TempDir
    Path temp;

    @Test
    void validDeflateDz_producesExpectedLogicalBytesAndCanFrame() throws Exception {
        byte[] logical = canRecord();
        Path file = buildSimpleCanDz(logical, deflate(logical), 0, 0, logical.length);
        var result = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, result.frames().size(), String.valueOf(result.diagnostics()));
        assertEquals(0x123L, result.frames().getFirst().canId());
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.code().name().startsWith("DZ_")));
    }

    @Test
    void decompressionProducesExpectedLogicalBytes() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length);
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertTrue(r.success(), String.valueOf(r.diagnostic()));
            assertEquals(logical.length, r.uncompressed().length);
            assertEquals(logical[9] & 0xFF, r.uncompressed()[9] & 0xFF);
        }
    }

    @Test
    void decompressedLengthMismatch_isDiagnostic() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length + 8);
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(access, dzNode(file, logical.length + 8, compressed.length, 0), 1L);
            assertFalse(r.success());
            assertEquals(MdfDecodeDiagnosticCode.DZ_LENGTH_MISMATCH, r.diagnostic().code());
        }
    }

    @Test
    void corruptCompressedData_reportsFailure() throws Exception {
        byte[] logical = canRecord();
        byte[] corrupt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Path file = buildSimpleCanDz(logical, corrupt, 0, 0, logical.length);
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(access, dzNode(file, logical.length, corrupt.length, 0), 1L);
            assertFalse(r.success());
            assertTrue(
                    r.diagnostic().code() == MdfDecodeDiagnosticCode.DZ_CORRUPT
                            || r.diagnostic().code() == MdfDecodeDiagnosticCode.DZ_DECOMPRESSION_FAILED
                            || r.diagnostic().code() == MdfDecodeDiagnosticCode.DZ_LENGTH_MISMATCH
            );
        }
    }

    @Test
    void truncatedDz_reportsTruncated() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = temp.resolve("trunc-dz.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[64]);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 0, 4, 0, 64);
            ByteBuffer meta = ByteBuffer.allocate(24 + 4).order(ByteOrder.LITTLE_ENDIAN);
            meta.put((byte) 'D');
            meta.put((byte) 'T');
            meta.put((byte) 0);
            meta.put((byte) 0);
            meta.putInt(0);
            meta.putLong(logical.length);
            meta.putLong(compressed.length);
            meta.put(compressed, 0, 4);
            long dz = b.writeBlock("##DZ", new long[0], meta.array());
            long cg = b.writeCg(0, time, 1, 1, 0x6, 16, 0);
            long dg = b.writeDg(0, cg, dz, 0);
            b.writeHd(dg, 0, 0);
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertFalse(r.success());
            assertEquals(MdfDecodeDiagnosticCode.DZ_TRUNCATED, r.diagnostic().code());
        }
    }

    @Test
    void unsupportedCompressionType_reportsDiagnostic() throws Exception {
        byte[] logical = canRecord();
        Path file = buildSimpleCanDz(logical, logical, 99, 0, logical.length);
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(access, dzNode(file, logical.length, logical.length, 99), 1L);
            assertFalse(r.success());
            assertEquals(MdfDecodeDiagnosticCode.DZ_UNSUPPORTED_COMPRESSION, r.diagnostic().code());
        }
    }

    @Test
    void dzThroughDl_decodesFrame() throws Exception {
        byte[] logical = canRecord();
        Path file = temp.resolve("dz-dl.mf4");
        writeCanChannelsWithRoot(file, logical, deflate(logical), "DL");
        var result = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(1, result.frames().size(), String.valueOf(result.diagnostics()));
        assertEquals(0x123L, result.frames().getFirst().canId());
    }

    @Test
    void dzThroughHlDl_decodesFrame() throws Exception {
        byte[] logical = canRecord();
        Path file = temp.resolve("dz-hl.mf4");
        writeCanChannelsWithRoot(file, logical, deflate(logical), "HL");
        var result = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(1, result.frames().size(), String.valueOf(result.diagnostics()));
        assertEquals(0x123L, result.frames().getFirst().canId());
    }

    @Test
    void oversizedDeflateDz_spillsToTempAndDecodesCanFrame() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length);
        long previous = MdfDzDecompressor.softOriginalBytesCapOverride;
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(
                    access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertTrue(r.success(), String.valueOf(r.diagnostic()));
            assertTrue(r.spilled());
            assertNotNull(r.spilledFile());
            assertTrue(Files.exists(r.spilledFile()));
            assertEquals(logical.length, r.spilledLength());

            MdfPayloadSegment segment = r.toSegment(findDz(file));
            assertTrue(segment.spillBacked());
            try (MdfSegmentCursor cursor = new MdfSegmentCursor(access, segment)) {
                var view = cursor.view(0, logical.length, "spill-view");
                assertEquals(logical.length, view.length());
                assertEquals(logical[9] & 0xFF, view.buffer()[view.offset() + 9] & 0xFF);
            }

            var decode = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
            assertEquals(1, decode.frames().size(), String.valueOf(decode.diagnostics()));
            assertEquals(0x123L, decode.frames().getFirst().canId());
            segment.discardSpill();
            assertFalse(Files.exists(r.spilledFile()));
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previous;
        }
    }

    @Test
    void oversizedTransposeDz_stillRejectedAboveSoftCap() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 1, 16, logical.length);
        long previous = MdfDzDecompressor.softOriginalBytesCapOverride;
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(
                    access, dzNode(file, logical.length, compressed.length, 1), 1L);
            assertFalse(r.success());
            assertEquals(MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH, r.diagnostic().code());
            assertTrue(r.diagnostic().reason().contains("spill supports Deflate-only"));
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previous;
        }
    }

    @Test
    void decodeAll_oversizedDeflateDz_discardsSpillTempFiles() throws Exception {
        byte[] logical = canRecord();
        Path file = buildSimpleCanDz(logical, deflate(logical), 0, 0, logical.length);
        long previousCap = MdfDzDecompressor.softOriginalBytesCapOverride;
        Path previousDir = MdfDzDecompressor.spillDirectoryOverride;
        Path spillDir = temp.resolve("spill-ok");
        Files.createDirectories(spillDir);
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        MdfDzDecompressor.spillDirectoryOverride = spillDir;
        try {
            var decode = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
            assertEquals(1, decode.frames().size(), String.valueOf(decode.diagnostics()));
            try (var stream = Files.list(spillDir)) {
                assertEquals(0, stream.count(), "spill temps must be discarded after decode");
            }
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previousCap;
            MdfDzDecompressor.spillDirectoryOverride = previousDir;
        }
    }

    @Test
    void missingCreatableSpillDirectory_isCreatedAndDecompressionSucceeds() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length);
        Path spill = temp.resolve("selected").resolve("StellarAIAnalysis").resolve("temp").resolve("dz");
        assertFalse(Files.exists(spill));
        long previousCap = MdfDzDecompressor.softOriginalBytesCapOverride;
        Path previousDir = MdfDzDecompressor.spillDirectoryOverride;
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        MdfDzDecompressor.spillDirectoryOverride = spill;
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(
                    access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertTrue(r.success(), String.valueOf(r.diagnostic()));
            assertTrue(r.diagnostic() == null);
            assertTrue(Files.isDirectory(spill));
            assertNotNull(r.spilledFile());
            assertTrue(r.spilledFile().toAbsolutePath().normalize().startsWith(spill.toAbsolutePath().normalize()));
            assertTrue(Files.isRegularFile(r.spilledFile()));
            assertEquals(logical.length, Files.size(r.spilledFile()));
            byte[] spilled = Files.readAllBytes(r.spilledFile());
            assertEquals(logical[9] & 0xFF, spilled[9] & 0xFF);
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previousCap;
            MdfDzDecompressor.spillDirectoryOverride = previousDir;
        }
    }

    @Test
    void spillDirectoryUnavailable_whenParentIsAFile_returnsDiagnosticNotSilentSuccess() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length);
        Path parentFile = temp.resolve("not-a-directory");
        Files.writeString(parentFile, "regular-file");
        Path spill = parentFile.resolve("dz-spill");
        long previousCap = MdfDzDecompressor.softOriginalBytesCapOverride;
        Path previousDir = MdfDzDecompressor.spillDirectoryOverride;
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        MdfDzDecompressor.spillDirectoryOverride = spill;
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(
                    access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertFalse(r.success());
            assertEquals(MdfDecodeDiagnosticCode.DZ_DECOMPRESSION_FAILED, r.diagnostic().code());
            assertTrue(r.diagnostic().reason().toLowerCase().contains("spill"));
            assertTrue(r.spilledFile() == null);
            assertEquals(0, r.uncompressed().length);
            assertTrue(Files.isRegularFile(parentFile));
            assertFalse(Files.isDirectory(spill));
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previousCap;
            MdfDzDecompressor.spillDirectoryOverride = previousDir;
        }
    }

    @Test
    void configuredAnalysisStorageTemp_receivesSpillInsteadOfOsTemp() throws Exception {
        byte[] logical = canRecord();
        byte[] compressed = deflate(logical);
        Path file = buildSimpleCanDz(logical, compressed, 0, 0, logical.length);
        Path storageTemp = temp.resolve("selected").resolve("StellarAIAnalysis").resolve("temp");
        assertFalse(Files.exists(storageTemp));
        long previousCap = MdfDzDecompressor.softOriginalBytesCapOverride;
        Path previousDir = MdfDzDecompressor.spillDirectoryOverride;
        String previousTemp = System.getProperty("stellarai.runtime.temp");
        MdfDzDecompressor.softOriginalBytesCapOverride = 8L;
        MdfDzDecompressor.spillDirectoryOverride = null;
        System.setProperty("stellarai.runtime.temp", storageTemp.toAbsolutePath().toString());
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            var r = MdfDzDecompressor.decompress(
                    access, dzNode(file, logical.length, compressed.length, 0), 1L);
            assertTrue(r.success(), String.valueOf(r.diagnostic()));
            assertNotNull(r.spilledFile());
            Path spilled = r.spilledFile().toAbsolutePath().normalize();
            Path configured = storageTemp.toAbsolutePath().normalize();
            assertTrue(spilled.startsWith(configured));
            assertTrue(configured.endsWith(Path.of("StellarAIAnalysis", "temp")));
            assertEquals(logical.length, Files.size(r.spilledFile()));
        } finally {
            MdfDzDecompressor.softOriginalBytesCapOverride = previousCap;
            MdfDzDecompressor.spillDirectoryOverride = previousDir;
            if (previousTemp == null) {
                System.clearProperty("stellarai.runtime.temp");
            } else {
                System.setProperty("stellarai.runtime.temp", previousTemp);
            }
        }
    }

    @Test
    void resolvePayloadSegments_surfacesDzFailureDiagnostic() throws Exception {
        byte[] logical = canRecord();
        Path file = buildSimpleCanDz(logical, logical, 42, 0, logical.length);
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            long dz = findDz(file);
            MdfDataSourceSchema schema = new MdfDataSourceSchema(
                    1L,
                    dz,
                    MdfBlockType.DZ,
                    0L,
                    0L,
                    true,
                    List.of(new MdfDataSourceNode(
                            MdfBlockType.DZ, dz, 0, 0, null, 42, (long) logical.length, (long) logical.length))
            );
            List<MdfDecodeDiagnostic> diags = new ArrayList<>();
            var segs = MdfStreamingRecordReader.resolvePayloadSegments(access, schema, diags::add);
            assertTrue(segs.isEmpty());
            assertTrue(diags.stream().anyMatch(d ->
                    d.code() == MdfDecodeDiagnosticCode.DZ_UNSUPPORTED_COMPRESSION));
        }
    }

    private Path buildSimpleCanDz(byte[] logical, byte[] compressed, int zipType, int zipParam, long orgLen)
            throws Exception {
        Path file = temp.resolve("dz-" + zipType + "-" + orgLen + "-" + System.nanoTime() + ".mf4");
        writeCanChannelsWithRoot(file, logical, compressed, "DZ", zipType, zipParam, orgLen);
        return file;
    }

    private void writeCanChannelsWithRoot(Path file, byte[] logical, byte[] compressed, String root)
            throws Exception {
        writeCanChannelsWithRoot(file, logical, compressed, root, 0, 0, logical.length);
    }

    private void writeCanChannelsWithRoot(
            Path file,
            byte[] logical,
            byte[] compressed,
            String root,
            int zipType,
            int zipParam,
            long orgLen
    ) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);
            long payload = b.writeCn(0L, 0L, b.writeTx("Event.DataBytes"), 0, 0, 0, 0, 14, 0, 16, 0, 0);
            long dlc = b.writeCn(payload, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 13, 0, 4, 0, 0);
            long dir = b.writeCn(dlc, 0L, b.writeTx("Event.Dir"), 0, 0, 0, 0, 12, 0, 1, 0, 0);
            long id = b.writeCn(dir, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("BusEvent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long dz = b.writeDz("DT", zipType, zipParam, orgLen, compressed);
            long dataRoot = switch (root) {
                case "DL" -> b.writeDl(0, new long[]{dz}, 0);
                case "HL" -> b.writeHl(b.writeDl(0, new long[]{dz}, 0), 0);
                default -> dz;
            };
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            long cg = b.writeCg(0, time, 0, si, 1, 1, 0x6, 16, 0);
            long dg = b.writeDg(0, cg, dataRoot, 0);
            b.writeHd(dg, 0, 0);
        }
    }

    private static MdfDataSourceNode dzNode(Path file, long orgLen, long compLen, int zipType) throws Exception {
        return new MdfDataSourceNode(
                MdfBlockType.DZ, findDz(file), 0, 0, null, zipType, orgLen, compLen);
    }

    private static long findDz(Path file) throws Exception {
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            for (long p = 64; p + 4 < access.fileSize(); p++) {
                byte[] m = access.readFully(p, 4, "scan");
                if (m[0] == '#' && m[1] == '#' && m[2] == 'D' && m[3] == 'Z') {
                    return p;
                }
            }
        }
        throw new IllegalStateException("DZ not found");
    }

    private static byte[] canRecord() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(1.5d);
        buf.put((byte) 1);
        buf.putShort((short) 0x123);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.put((byte) 0x11);
        buf.put((byte) 0x22);
        return buf.array();
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }
}
