package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.ParserException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Readable payload window: physical DT/SD file range, in-memory DZ inflate, or spilled DZ temp file.
 * Sequential decoding should use {@link MdfSegmentCursor} rather than {@link #read}.
 */
public record MdfPayloadSegment(
        long offset,
        long endExclusive,
        byte[] memoryBytes,
        long sourceBlockAddress,
        Path spilledPath
) {

    public MdfPayloadSegment {
        if (memoryBytes != null && spilledPath != null) {
            throw new IllegalArgumentException("payload cannot be both memory- and spill-backed");
        }
    }

    public static MdfPayloadSegment file(long offset, long endExclusive) {
        return new MdfPayloadSegment(offset, endExclusive, null, offset, null);
    }

    /** Defensive copy for callers that do not transfer ownership. */
    public static MdfPayloadSegment memory(byte[] bytes, long sourceBlockAddress) {
        Objects.requireNonNull(bytes, "bytes");
        return memoryOwned(Arrays.copyOf(bytes, bytes.length), sourceBlockAddress);
    }

    /** Takes ownership of {@code bytes} without copying. Caller must not mutate after transfer. */
    public static MdfPayloadSegment memoryOwned(byte[] bytes, long sourceBlockAddress) {
        Objects.requireNonNull(bytes, "bytes");
        return new MdfPayloadSegment(0L, bytes.length, bytes, sourceBlockAddress, null);
    }

    /**
     * Owns a temporary file containing inflated DZ logical bytes
     * (delete via {@link #discardSpill()}).
     */
    public static MdfPayloadSegment spilled(Path spilledPath, long length, long sourceBlockAddress) {
        Objects.requireNonNull(spilledPath, "spilledPath");
        if (length < 0L) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        return new MdfPayloadSegment(0L, length, null, sourceBlockAddress, spilledPath);
    }

    public boolean memoryBacked() {
        return memoryBytes != null;
    }

    public boolean spillBacked() {
        return spilledPath != null;
    }

    public long length() {
        return Math.max(0L, endExclusive - offset);
    }

    public void discardSpill() {
        if (spilledPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(spilledPath);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public byte[] read(MdfFileAccess access, long absoluteOffset, int length, String context)
            throws ParserException {
        if (length < 0) {
            throw new ParserException(context + ": negative length");
        }
        if (absoluteOffset < offset || absoluteOffset + (long) length > endExclusive) {
            throw new ParserException(context + ": read outside payload segment ["
                    + offset + "," + endExclusive + ")");
        }
        if (memoryBytes != null) {
            int from = (int) (absoluteOffset - offset);
            return Arrays.copyOfRange(memoryBytes, from, from + length);
        }
        if (spilledPath != null) {
            try (MdfFileAccess spill = MdfFileAccess.open(spilledPath)) {
                return spill.readFully(absoluteOffset, length, context);
            }
        }
        return access.readFully(absoluteOffset, length, context);
    }
}