package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.CanonicalCanFrame;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outcome of recovery for one DG (or aggregated file-level summary).
 * <p>
 * Recovery is record-oriented: {@link #recordsRecovered()} counts accepted
 * recovered records. {@link #frames()} is retained for legacy callers and may
 * be empty when frames are emitted only through the decode-path consumer.
 */
public record MdfRecoveryResult(
        MdfRecoveryStatus status,
        List<CanonicalCanFrame> frames,
        long recordsRecovered,
        long recordsInspected,
        long recordsRejected,
        long bytesInspected,
        long effectiveDataStart,
        long effectiveDataEnd,
        List<String> reasons,
        List<MdfRecoveryDiagnostic> diagnostics,
        boolean completedNormally
) {

    public MdfRecoveryResult {
        Objects.requireNonNull(status, "status");
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static MdfRecoveryResult notRequired() {
        return new MdfRecoveryResult(
                MdfRecoveryStatus.NOT_REQUIRED,
                List.of(),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                List.of("Recovery not required"),
                List.of(),
                true
        );
    }
}