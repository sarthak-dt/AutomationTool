package com.stellar.platform.dbc.parse;

/**
 * Centralized numeric parsing for DBC factor/offset/min/max and related tokens.
 * Supports integer, decimal, signed, and scientific notation.
 */
public final class DbcNumberParser {

    private DbcNumberParser() {
    }

    public static double parseDouble(String raw, DbcDiagnosticCode failureCode, String context)
            throws DbcParseException {
        if (raw == null || raw.isBlank()) {
            throw new DbcParseException(failureCode, "Missing numeric value " + context);
        }
        String token = raw.trim();
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new DbcParseException(
                    failureCode,
                    "Invalid numeric value '" + token + "' " + context,
                    e
            );
        }
    }

    public static int parseInt(String raw, DbcDiagnosticCode failureCode, String context)
            throws DbcParseException {
        if (raw == null || raw.isBlank()) {
            throw new DbcParseException(failureCode, "Missing integer value " + context);
        }
        String token = raw.trim();
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new DbcParseException(
                    failureCode,
                    "Invalid integer value '" + token + "' " + context,
                    e
            );
        }
    }
}
