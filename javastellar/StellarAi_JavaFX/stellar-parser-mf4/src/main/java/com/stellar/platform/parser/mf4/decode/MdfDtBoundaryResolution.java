package com.stellar.platform.parser.mf4.decode;

/**
 * Result of resolving the physical end of an unfinalized / incomplete DT payload region.
 */
public record MdfDtBoundaryResolution(
        long endExclusive,
        long falseMarkerCandidates,
        long acceptedBoundaryOffset,
        boolean usedEofFallback,
        boolean declaredLengthUnusable
) {

    public static MdfDtBoundaryResolution ofDeclared(long endExclusive, boolean declaredLengthUnusable) {
        return new MdfDtBoundaryResolution(endExclusive, 0L, -1L, false, declaredLengthUnusable);
    }
}
