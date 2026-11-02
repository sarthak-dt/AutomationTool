package com.stellar.platform.dbc.parse;

import java.util.Objects;

/**
 * Diagnostic with enough context for future UI reporting.
 */
public record DbcDiagnostic(
        DbcDiagnosticCode code,
        String message,
        Long lineNumber,
        String context
) {

    public DbcDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static DbcDiagnostic of(DbcDiagnosticCode code, String message) {
        return new DbcDiagnostic(code, message, null, null);
    }

    public static DbcDiagnostic ofLine(DbcDiagnosticCode code, long lineNumber, String message) {
        return new DbcDiagnostic(code, message, lineNumber, null);
    }

    public static DbcDiagnostic ofContext(
            DbcDiagnosticCode code, String message, Long lineNumber, String context
    ) {
        return new DbcDiagnostic(code, message, lineNumber, context);
    }
}
