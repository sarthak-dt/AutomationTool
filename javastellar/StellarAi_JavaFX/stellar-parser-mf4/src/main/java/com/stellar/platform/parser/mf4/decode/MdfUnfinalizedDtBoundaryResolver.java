package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.block.MdfBlockCandidateValidator;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the safe physical end of an incomplete / unfinalized DT payload region.
 * <p>
 * Boundary recovery only: does not decode records. Candidates located by a buffered
 * {@code ##} scan are accepted only after {@link MdfBlockCandidateValidator} succeeds.
 */
public final class MdfUnfinalizedDtBoundaryResolver {

    /** Bounded scan buffer suitable for multi-GB files (no full-file buffering). */
    static final int SCAN_CHUNK_BYTES = 1_048_576;

    private MdfUnfinalizedDtBoundaryResolver() {
    }

    /**
     * Resolves the exclusive end offset of a DT recovery region starting at {@code payloadStart}.
     *
     * <ul>
     *   <li>A structurally valid later MDF block → end at that block address</li>
     *   <li>No valid later block and {@code allowEofFallback} → physical EOF</li>
     *   <li>No valid later block and finalized/consistent → remain at {@code payloadStart}
     *       (caller must not silently extend to EOF)</li>
     * </ul>
     */
    public static MdfDtBoundaryResolution resolveUnfinalizedDtEnd(
            MdfFileAccess access,
            long payloadStart,
            boolean declaredLengthUnusable,
            MdfDtBoundaryRequest request
    ) throws ParserException {
        Objects.requireNonNull(access, "access");
        MdfDtBoundaryRequest req = request == null ? MdfDtBoundaryRequest.defaults() : request;
        long fileSize = access.fileSize();
        long start = Math.max(0L, payloadStart);
        if (start > fileSize) {
            start = fileSize;
        }

        NextBlockScan scan = findNextStructurallyValidBlock(access, start, req.cancellation());
        if (scan.acceptedOffset >= 0L) {
            long end = Math.max(start, scan.acceptedOffset);
            return new MdfDtBoundaryResolution(
                    end,
                    scan.falseMarkers,
                    scan.acceptedOffset,
                    false,
                    declaredLengthUnusable
            );
        }
        if (req.allowEofFallback()) {
            return new MdfDtBoundaryResolution(
                    fileSize,
                    scan.falseMarkers,
                    -1L,
                    true,
                    declaredLengthUnusable
            );
        }
        // Finalized/consistent: do not extend past the declared/payload start.
        return new MdfDtBoundaryResolution(
                start,
                scan.falseMarkers,
                -1L,
                false,
                declaredLengthUnusable
        );
    }

    /**
     * Buffered search for the first structurally valid MDF block at/after {@code fromInclusive}.
     */
    public static Optional<MdfBlockHeader> findNextValidBlock(
            MdfFileAccess access,
            long fromInclusive,
            CancellationToken cancellation
    ) throws ParserException {
        NextBlockScan scan = findNextStructurallyValidBlock(access, fromInclusive, cancellation);
        if (scan.acceptedOffset < 0L) {
            return Optional.empty();
        }
        return MdfBlockCandidateValidator.validateBlockCandidate(access, scan.acceptedOffset);
    }

    private static NextBlockScan findNextStructurallyValidBlock(
            MdfFileAccess access,
            long fromInclusive,
            CancellationToken cancellation
    ) throws ParserException {
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        long fileSize = access.fileSize();
        long at = Math.max(0L, fromInclusive);
        if (at >= fileSize) {
            return new NextBlockScan(-1L, 0L);
        }

        long falseMarkers = 0L;
        // Overlap of 1 byte so "##" spanning chunk boundaries is still found.
        final int overlap = 1;
        long pos = at;
        while (pos < fileSize) {
            if (token.isCancellationRequested()) {
                throw new ParserException("Cancellation requested during unfinalized DT boundary scan at " + pos);
            }
            int toRead = (int) Math.min(SCAN_CHUNK_BYTES, fileSize - pos);
            if (toRead < 2) {
                break;
            }
            byte[] chunk = access.readFully(pos, toRead, "dt-boundary-scan@" + pos);
            int limit = toRead - 1;
            for (int i = 0; i < limit; i++) {
                if (chunk[i] != '#' || chunk[i + 1] != '#') {
                    continue;
                }
                long candidate = pos + i;
                if (candidate < at) {
                    continue;
                }
                Optional<MdfBlockHeader> valid =
                        MdfBlockCandidateValidator.validateBlockCandidate(access, candidate);
                if (valid.isPresent()) {
                    return new NextBlockScan(candidate, falseMarkers);
                }
                falseMarkers++;
            }
            if (toRead < SCAN_CHUNK_BYTES) {
                break;
            }
            long next = MdfSafeMath.addExact(pos, toRead - overlap, "dtBoundaryScanAdvance");
            if (next <= pos) {
                break;
            }
            pos = next;
        }
        return new NextBlockScan(-1L, falseMarkers);
    }

    private record NextBlockScan(long acceptedOffset, long falseMarkers) {
    }
}
