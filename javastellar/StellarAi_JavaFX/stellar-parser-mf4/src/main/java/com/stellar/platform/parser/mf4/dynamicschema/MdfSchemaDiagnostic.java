package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;

import java.util.Objects;

/**
 * Schema-builder diagnostic with DG/CG/CN context.
 */
public record MdfSchemaDiagnostic(
        MdfSchemaDiagnosticCode code,
        MdfIssueSeverity severity,
        String reason,
        Long dataGroupAddress,
        Long channelGroupAddress,
        Long channelAddress
) {

    public MdfSchemaDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
    }
}
