package com.stellar.platform.dbc.parse;

/**
 * Thrown when a DBC file cannot be parsed into a usable database.
 */
public class DbcParseException extends Exception {

    private final DbcDiagnosticCode code;
    private final long lineNumber;
    private final String sourceLine;

    public DbcParseException(String message) {
        this(DbcDiagnosticCode.MALFORMED_DBC, message, -1L, null, null);
    }

    public DbcParseException(String message, Throwable cause) {
        this(DbcDiagnosticCode.MALFORMED_DBC, message, -1L, null, cause);
    }

    public DbcParseException(DbcDiagnosticCode code, String message) {
        this(code, message, -1L, null, null);
    }

    public DbcParseException(DbcDiagnosticCode code, String message, Throwable cause) {
        this(code, message, -1L, null, cause);
    }

    public DbcParseException(
            DbcDiagnosticCode code,
            String message,
            long lineNumber,
            String sourceLine
    ) {
        this(code, message, lineNumber, sourceLine, null);
    }

    public DbcParseException(
            DbcDiagnosticCode code,
            String message,
            long lineNumber,
            String sourceLine,
            Throwable cause
    ) {
        super(formatMessage(code, message, lineNumber, sourceLine), cause);
        this.code = code == null ? DbcDiagnosticCode.MALFORMED_DBC : code;
        this.lineNumber = lineNumber;
        this.sourceLine = sourceLine;
    }

    public DbcDiagnosticCode code() {
        return code;
    }

    public long lineNumber() {
        return lineNumber;
    }

    public String sourceLine() {
        return sourceLine;
    }

    private static String formatMessage(
            DbcDiagnosticCode code,
            String message,
            long lineNumber,
            String sourceLine
    ) {
        StringBuilder sb = new StringBuilder();
        if (code != null) {
            sb.append(code.name());
            sb.append(": ");
        }
        sb.append(message == null ? "" : message);
        if (lineNumber > 0) {
            sb.append(" at line ").append(lineNumber);
        }
        if (sourceLine != null && !sourceLine.isBlank()) {
            sb.append(": ").append(sourceLine);
        }
        return sb.toString();
    }
}
