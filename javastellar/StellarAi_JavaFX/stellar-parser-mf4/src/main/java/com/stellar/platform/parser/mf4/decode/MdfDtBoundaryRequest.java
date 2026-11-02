package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.spi.CancellationToken;

import java.util.Objects;

/**
 * Options for unfinalized DT physical-boundary resolution.
 */
public record MdfDtBoundaryRequest(
        boolean allowEofFallback,
        CancellationToken cancellation
) {

    public MdfDtBoundaryRequest {
        cancellation = cancellation == null ? CancellationToken.NONE : cancellation;
    }

    public static MdfDtBoundaryRequest of(boolean allowEofFallback, CancellationToken cancellation) {
        return new MdfDtBoundaryRequest(allowEofFallback, cancellation);
    }

    /**
     * Recovery default: EOF fallback allowed only when the container is unfinalized/incomplete.
     */
    public static MdfDtBoundaryRequest forRecovery(boolean unfinalizedOrIncomplete, CancellationToken cancellation) {
        return of(unfinalizedOrIncomplete, cancellation);
    }

    public static MdfDtBoundaryRequest defaults() {
        return of(false, CancellationToken.NONE);
    }

    public MdfDtBoundaryRequest withCancellation(CancellationToken token) {
        return new MdfDtBoundaryRequest(allowEofFallback, Objects.requireNonNullElse(token, CancellationToken.NONE));
    }
}
