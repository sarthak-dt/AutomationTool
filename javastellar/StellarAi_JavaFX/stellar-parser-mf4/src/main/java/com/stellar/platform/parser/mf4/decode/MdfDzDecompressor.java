package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Standards-driven DZ block decompression (ASAM MDF 4.x).
 * <p>
 * Inflates one DZ block at a time. Small logical payloads stay in a bounded in-memory buffer.
 * Oversized Deflate DZ blocks (declared original length above the soft heap cap) spill to a
 * temporary file so record streaming remains sequential and does not require materialising the
 * entire MF4 measurement file. Transpose+Deflate still requires a contiguous logical buffer and
 * remains limited to the soft cap.
 * <p>
 * Supported {@code dz_zip_type} values:
 * <ul>
 *   <li>0 — Deflate</li>
 *   <li>1 — Transpose + Deflate ({@code dz_zip_parameter} = column size in bytes)</li>
 * </ul>
 */
public final class MdfDzDecompressor {

    /** Soft in-memory cap per DZ logical block to protect heap. */
    public static final long MAX_ORIGINAL_BYTES_PER_BLOCK = 256L * 1024L * 1024L;

    /**
     * Hard upper bound for spill-backed Deflate DZ blocks (Java array / FileChannel practical max).
     * Larger single DZ blocks are rejected with a diagnostic.
     */
    public static final long MAX_SPILL_ORIGINAL_BYTES = Integer.MAX_VALUE;

    public static final int ZIP_DEFLATE = 0;
    public static final int ZIP_TRANSPOSE_DEFLATE = 1;

    /**
     * Package-visible override for synthetic tests that exercise spill without multi-hundred-MB
     * fixtures. {@code <= 0} means use {@link #MAX_ORIGINAL_BYTES_PER_BLOCK}.
     */
    static volatile long softOriginalBytesCapOverride = -1L;

    /**
     * Package-visible override for synthetic tests of spill-directory failures.
     * {@code null} means the JVM default temp directory.
     */
    static volatile Path spillDirectoryOverride = null;

    private MdfDzDecompressor() {
    }

    private static long softOriginalBytesCap() {
        long override = softOriginalBytesCapOverride;
        return override > 0L ? override : MAX_ORIGINAL_BYTES_PER_BLOCK;
    }

    public record DzDecodeResult(
            byte[] uncompressed,
            Path spilledFile,
            long spilledLength,
            MdfDecodeDiagnostic diagnostic
    ) {
        public DzDecodeResult {
            uncompressed = uncompressed == null ? new byte[0] : uncompressed;
        }

        public boolean success() {
            return diagnostic == null;
        }

        public boolean spilled() {
            return spilledFile != null;
        }

        public MdfPayloadSegment toSegment(long sourceBlockAddress) {
            if (!success()) {
                throw new IllegalStateException("Cannot build segment from failed DZ decode");
            }
            if (spilledFile != null) {
                return MdfPayloadSegment.spilled(spilledFile, spilledLength, sourceBlockAddress);
            }
            return MdfPayloadSegment.memoryOwned(uncompressed, sourceBlockAddress);
        }
    }

    /**
     * Decompresses a DZ data-source node into logical DT/SD bytes.
     */
    public static DzDecodeResult decompress(
            MdfFileAccess access,
            MdfDataSourceNode node,
            Long dataGroupAddress
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(node, "node");
        if (node.blockType() != MdfBlockType.DZ) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE,
                    "Not a DZ block: " + node.blockType());
        }

        MdfBlockHeader header = MdfBlockHeaderReader.read(access, node.address());
        if (header.declaredDataLength() < 24L) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_TRUNCATED,
                    "DZ data section shorter than 24-byte header");
        }

        long metaOffset = header.dataSectionOffset();
        byte[] meta = access.readFully(metaOffset, 24, "DZ-meta@" + node.address());
        int zipType = Byte.toUnsignedInt(meta[2]);
        int zipParameter = (meta[4] & 0xFF)
                | ((meta[5] & 0xFF) << 8)
                | ((meta[6] & 0xFF) << 16)
                | ((meta[7] & 0xFF) << 24);
        long originalLen = leLong(meta, 8);
        long compressedLen = leLong(meta, 16);

        if (originalLen < 0L || compressedLen < 0L) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "Negative DZ length original=" + originalLen + " compressed=" + compressedLen);
        }
        if (originalLen == 0L) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "DZ original uncompressed length is 0");
        }
        if (compressedLen > Integer.MAX_VALUE) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "DZ compressed length exceeds addressable Java array size");
        }
        long softCap = softOriginalBytesCap();
        boolean requiresSpill = originalLen > softCap;
        if (requiresSpill && zipType == ZIP_TRANSPOSE_DEFLATE) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "Transpose DZ original length " + originalLen
                            + " exceeds in-memory per-block limit " + softCap
                            + " (spill supports Deflate-only DZ)");
        }
        if (requiresSpill && originalLen > MAX_SPILL_ORIGINAL_BYTES) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "DZ original length " + originalLen + " exceeds spill limit "
                            + MAX_SPILL_ORIGINAL_BYTES);
        }
        if (!requiresSpill && originalLen > Integer.MAX_VALUE) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "DZ length exceeds addressable Java array size");
        }

        long compressedStart;
        try {
            compressedStart = MdfSafeMath.addExact(metaOffset, 24L, "dzCompressedStart");
        } catch (ArithmeticException e) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                    "DZ compressed payload offset overflow");
        }

        long available = Math.max(0L, access.fileSize() - compressedStart);
        long declaredPayloadAfterMeta = Math.max(0L, header.declaredDataLength() - 24L);
        long readableCompressed = Math.min(compressedLen, Math.min(available, declaredPayloadAfterMeta));
        if (readableCompressed < compressedLen) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_TRUNCATED,
                    "DZ compressed payload truncated need=" + compressedLen + " readable=" + readableCompressed);
        }
        if (node.compressedLength() != null && node.compressedLength() > 0
                && !node.compressedLength().equals(compressedLen)) {
            // Prefer on-disk header; warn via mismatch only when metadata disagrees badly.
        }

        byte[] compressed = compressedLen == 0L
                ? new byte[0]
                : access.readFully(compressedStart, (int) compressedLen, "DZ-payload@" + node.address());

        if (zipType != ZIP_DEFLATE && zipType != ZIP_TRANSPOSE_DEFLATE) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_UNSUPPORTED_COMPRESSION,
                    "Unsupported dz_zip_type=" + zipType);
        }

        if (requiresSpill) {
            try {
                Path spilled = inflateToTempFile(compressed, originalLen);
                return new DzDecodeResult(new byte[0], spilled, originalLen, null);
            } catch (DataFormatException e) {
                return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_CORRUPT,
                        "Corrupt DZ deflate stream: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_DECOMPRESSION_FAILED,
                        "DZ spill decompression failed: " + e.getMessage());
            }
        }

        byte[] inflated;
        try {
            inflated = inflate(compressed, (int) originalLen);
        } catch (DataFormatException e) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_CORRUPT,
                    "Corrupt DZ deflate stream: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_DECOMPRESSION_FAILED,
                    "DZ decompression failed: " + e.getMessage());
        }

        if (inflated.length != (int) originalLen) {
            return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_LENGTH_MISMATCH,
                    "Decompressed length " + inflated.length + " != declared original " + originalLen);
        }

        if (zipType == ZIP_TRANSPOSE_DEFLATE) {
            if (zipParameter <= 0) {
                return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_INVALID_LENGTH,
                        "Transpose DZ requires positive dz_zip_parameter, got " + zipParameter);
            }
            try {
                inflated = detranspose(inflated, zipParameter);
            } catch (IllegalArgumentException e) {
                return fail(dataGroupAddress, node.address(), MdfDecodeDiagnosticCode.DZ_DECOMPRESSION_FAILED,
                        e.getMessage());
            }
        }

        return new DzDecodeResult(inflated, null, 0L, null);
    }

    /**
     * Analysis Storage publishes {@code stellarai.runtime.temp}. When that property is set,
     * spill files stay there and are not created in the operating-system temp directory.
     */
    private static Path spillDirectory() {
        Path override = spillDirectoryOverride;
        if (override != null) {
            return override;
        }
        String configured = System.getProperty("stellarai.runtime.temp", "").trim();
        if (configured.isEmpty()) {
            return null;
        }
        return Path.of(configured);
    }

    /**
     * Streams Deflate output to a temp file so oversized DZ logical payloads do not require a
     * contiguous heap allocation for the full original length.
     */
    private static Path inflateToTempFile(byte[] compressed, long expectedOriginal)
            throws DataFormatException, IOException {
        Path dir = spillDirectory();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = dir == null
                ? Files.createTempFile("stellar-mdf-dz-", ".bin")
                : Files.createTempFile(dir, "stellar-mdf-dz-", ".bin");
        try {
            long written = inflateIntoChannel(compressed, expectedOriginal, temp, false);
            if (written != expectedOriginal) {
                written = inflateIntoChannel(compressed, expectedOriginal, temp, true);
            }
            if (written != expectedOriginal) {
                throw new DataFormatException(
                        "incomplete spill inflate wrote=" + written + " expected=" + expectedOriginal);
            }
            return temp;
        } catch (DataFormatException | IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best-effort
            }
            throw e;
        }
    }

    private static long inflateIntoChannel(
            byte[] compressed,
            long expectedOriginal,
            Path temp,
            boolean nowrap
    ) throws DataFormatException, IOException {
        Inflater inflater = new Inflater(nowrap);
        try (FileChannel out = FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE
        )) {
            inflater.setInput(compressed);
            byte[] chunk = new byte[64 * 1024];
            long written = 0L;
            while (!inflater.finished() && written < expectedOriginal) {
                int n = inflater.inflate(chunk);
                if (n == 0) {
                    if (inflater.needsDictionary()) {
                        throw new DataFormatException("DZ inflate needs dictionary");
                    }
                    if (inflater.needsInput()) {
                        break;
                    }
                    continue;
                }
                int allow = (int) Math.min(n, expectedOriginal - written);
                out.write(ByteBuffer.wrap(chunk, 0, allow));
                written += allow;
            }
            return written;
        } finally {
            inflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed, int expectedOriginal) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expectedOriginal];
            int written = inflater.inflate(out);
            if (!inflater.finished() && written < expectedOriginal) {
                // Some writers omit zlib wrapper; retry as raw deflate.
                Inflater raw = new Inflater(true);
                try {
                    raw.setInput(compressed);
                    written = raw.inflate(out);
                    if (!raw.finished() && written != expectedOriginal) {
                        throw new DataFormatException("incomplete raw inflate wrote=" + written);
                    }
                } finally {
                    raw.end();
                }
            }
            // Return whatever was written; caller validates against declared original length.
            if (written != expectedOriginal) {
                return Arrays.copyOf(out, Math.max(0, written));
            }
            return out;
        } finally {
            inflater.end();
        }
    }

    /**
     * Converts column-major transposed bytes back to row-major record layout.
     */
    static byte[] detranspose(byte[] transposed, int columnSize) {
        if (columnSize <= 0) {
            throw new IllegalArgumentException("columnSize must be > 0");
        }
        if (transposed.length % columnSize != 0) {
            throw new IllegalArgumentException(
                    "Transposed length " + transposed.length + " not divisible by columnSize " + columnSize);
        }
        int rows = transposed.length / columnSize;
        byte[] out = new byte[transposed.length];
        for (int col = 0; col < columnSize; col++) {
            for (int row = 0; row < rows; row++) {
                out[row * columnSize + col] = transposed[col * rows + row];
            }
        }
        return out;
    }

    private static long leLong(byte[] b, int off) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v |= (Byte.toUnsignedLong(b[off + i]) << (8 * i));
        }
        return v;
    }

    private static DzDecodeResult fail(Long dg, long address, MdfDecodeDiagnosticCode code, String reason) {
        return new DzDecodeResult(new byte[0], null, 0L, new MdfDecodeDiagnostic(
                code,
                MdfIssueSeverity.WARNING,
                reason,
                dg,
                null,
                null,
                address
        ));
    }
}
