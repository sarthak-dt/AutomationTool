package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;

import java.util.Objects;

/**
 * Decoder diagnostic with MF4 addressing context.
 */
public record MdfDecodeDiagnostic(
        MdfDecodeDiagnosticCode code,
        MdfIssueSeverity severity,
        String reason,
        Long dataGroupAddress,
        Long channelGroupAddress,
        Long channelAddress,
        long physicalOffset
) {

    public MdfDecodeDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
    }

    public ProcessingWarning toWarning() {
        return new ProcessingWarning(
                code.name(),
                reason,
                new SourceReference(-1L, physicalOffset)
        );
    }
}
