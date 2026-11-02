package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnostic;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnosticCode;

import java.util.Objects;

/**
 * Recovery-engine diagnostic with MF4 addressing context.
 */
public record MdfRecoveryDiagnostic(
        MdfRecoveryDiagnosticCode code,
        MdfIssueSeverity severity,
        String reason,
        Long dataGroupAddress,
        Long channelGroupAddress,
        Long recordId,
        long physicalOffset
) {

    public MdfRecoveryDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
    }

    public ProcessingWarning toWarning() {
        // Canonical code for skipped non-CAN CGs (same events as decode NON_CAN_CHANNEL_GROUP_SKIPPED).
        String warningCode = code == MdfRecoveryDiagnosticCode.NON_CAN_GROUP_SKIPPED
                ? MdfDecodeDiagnosticCode.NON_CAN_CHANNEL_GROUP_SKIPPED.name()
                : code.name();
        return new ProcessingWarning(
                warningCode,
                reason,
                new SourceReference(-1L, physicalOffset)
        );
    }

    public MdfDecodeDiagnostic toDecodeDiagnostic() {
        MdfDecodeDiagnosticCode mapped = switch (code) {
            case RECOVERY_REQUIRED -> MdfDecodeDiagnosticCode.RECOVERY_REQUIRED;
            case UNKNOWN_RECORD_ID -> MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID;
            case TRUNCATED_FINAL_RECORD -> MdfDecodeDiagnosticCode.TRUNCATED_RECORD;
            case UNSUPPORTED_RECOVERY_SOURCE -> MdfDecodeDiagnosticCode.UNSUPPORTED_DATA_SOURCE;
            case NON_CAN_GROUP_SKIPPED -> MdfDecodeDiagnosticCode.NON_CAN_CHANNEL_GROUP_SKIPPED;
            case CANCELLED -> MdfDecodeDiagnosticCode.CANCELLED;
            default -> MdfDecodeDiagnosticCode.RECOVERY_REQUIRED;
        };
        return new MdfDecodeDiagnostic(
                mapped,
                severity,
                "[" + code + "] " + reason,
                dataGroupAddress,
                channelGroupAddress,
                null,
                physicalOffset
        );
    }
}
