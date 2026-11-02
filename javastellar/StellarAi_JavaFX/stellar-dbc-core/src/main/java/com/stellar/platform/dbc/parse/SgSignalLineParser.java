package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured SG_ line parser. Avoids a single fragile regex for the full signal grammar.
 * <p>
 * Supports multiplex tokens: {@code M}, {@code mN}, and extended {@code mNM}.
 */
public final class SgSignalLineParser {

    private SgSignalLineParser() {
    }

    public static DbcSignal parse(String rawLine, long lineNo) throws DbcParseException {
        if (rawLine == null) {
            throw fail(DbcDiagnosticCode.DBC_SG_MALFORMED, "SG_ line is null", lineNo, null);
        }
        String line = rawLine.trim();
        if (line.endsWith(";")) {
            line = line.substring(0, line.length() - 1).trim();
        }
        if (!line.startsWith("SG_")) {
            throw fail(DbcDiagnosticCode.DBC_SG_MALFORMED, "Line does not start with SG_", lineNo, rawLine);
        }

        Cursor c = new Cursor(line, lineNo, rawLine);
        c.expectKeyword("SG_");
        c.skipWs();

        String name = c.readIdentifier(DbcDiagnosticCode.DBC_SG_INVALID_NAME, "signal name");
        c.skipWs();

        MuxToken mux = c.tryReadMux();
        c.skipWs();
        c.expectChar(':', DbcDiagnosticCode.DBC_SG_MALFORMED, "expected ':' after signal name/multiplex");
        c.skipWs();

        int startBit = c.readUnsignedInt(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "start-bit");
        c.expectChar('|', DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "expected '|' in bit layout");
        int bitLength = c.readUnsignedInt(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "bit-length");
        c.expectChar('@', DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "expected '@' in bit layout");
        char orderChar = c.readChar(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "byte-order");
        if (orderChar != '0' && orderChar != '1') {
            throw fail(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT,
                    "byte-order must be @0 or @1, found '@" + orderChar + "'", lineNo, rawLine);
        }
        char signChar = c.readChar(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT, "sign");
        if (signChar != '+' && signChar != '-') {
            throw fail(DbcDiagnosticCode.DBC_SG_INVALID_BIT_LAYOUT,
                    "sign must be '+' or '-', found '" + signChar + "'", lineNo, rawLine);
        }
        c.skipWs();

        c.expectChar('(', DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "expected '(' for factor/offset");
        String factorToken = c.readUntil(',', DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "factor");
        c.expectChar(',', DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "expected ',' in factor/offset");
        String offsetToken = c.readUntil(')', DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "offset");
        c.expectChar(')', DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "expected ')' after factor/offset");
        double factor = DbcNumberParser.parseDouble(
                factorToken, DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "in factor");
        double offset = DbcNumberParser.parseDouble(
                offsetToken, DbcDiagnosticCode.DBC_SG_INVALID_FACTOR_OFFSET, "in offset");
        c.skipWs();

        c.expectChar('[', DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "expected '[' for min/max");
        String minToken = c.readUntil('|', DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "minimum");
        c.expectChar('|', DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "expected '|' in min/max");
        String maxToken = c.readUntil(']', DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "maximum");
        c.expectChar(']', DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "expected ']' after min/max");
        double min = minToken.isBlank()
                ? 0d
                : DbcNumberParser.parseDouble(minToken, DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "in minimum");
        double max = maxToken.isBlank()
                ? 0d
                : DbcNumberParser.parseDouble(maxToken, DbcDiagnosticCode.DBC_SG_INVALID_RANGE, "in maximum");
        c.skipWs();

        String unit = c.readQuotedUnit();
        c.skipWs();
        List<String> receivers = c.readReceivers();

        if (!c.remainingBlank()) {
            throw fail(DbcDiagnosticCode.DBC_SG_MALFORMED,
                    "Unexpected trailing content '" + c.rest() + "'", lineNo, rawLine);
        }

        DbcByteOrder order = DbcByteOrder.fromDbcCode(orderChar == '1' ? 1 : 0);
        DbcValueType valueType = DbcValueType.fromDbcSign(signChar);
        DbcMultiplexRole role = mux == null ? DbcMultiplexRole.NONE : mux.role();
        Integer muxValue = mux == null ? null : mux.value();

        boolean secondaryMultiplexor = mux != null && mux.extendedMultiplexor();
        try {
            return new DbcSignal(
                    name,
                    startBit,
                    bitLength,
                    order,
                    valueType,
                    factor,
                    offset,
                    min,
                    max,
                    unit,
                    receivers,
                    role,
                    muxValue,
                    secondaryMultiplexor,
                    null,
                    DbcValueTable.empty()
            );
        } catch (RuntimeException e) {
            throw new DbcParseException(
                    DbcDiagnosticCode.INVALID_SIGNAL_DEFINITION,
                    e.getMessage() == null ? "Invalid signal definition" : e.getMessage(),
                    lineNo,
                    rawLine,
                    e
            );
        }
    }

    private static DbcParseException fail(
            DbcDiagnosticCode code,
            String message,
            long lineNo,
            String sourceLine
    ) {
        return new DbcParseException(code, message, lineNo, sourceLine);
    }

    private record MuxToken(DbcMultiplexRole role, Integer value, boolean extendedMultiplexor) {
    }

    private static final class Cursor {
        private final String line;
        private final long lineNo;
        private final String sourceLine;
        private int i;

        private Cursor(String line, long lineNo, String sourceLine) {
            this.line = line;
            this.lineNo = lineNo;
            this.sourceLine = sourceLine;
            this.i = 0;
        }

        private void skipWs() {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
        }

        private boolean remainingBlank() {
            int j = i;
            while (j < line.length() && Character.isWhitespace(line.charAt(j))) {
                j++;
            }
            return j >= line.length();
        }

        private String rest() {
            return line.substring(i).trim();
        }

        private void expectKeyword(String keyword) throws DbcParseException {
            if (!line.regionMatches(i, keyword, 0, keyword.length())) {
                throw fail(DbcDiagnosticCode.DBC_SG_MALFORMED, "expected " + keyword, lineNo, sourceLine);
            }
            i += keyword.length();
        }

        private void expectChar(char expected, DbcDiagnosticCode code, String detail)
                throws DbcParseException {
            if (i >= line.length() || line.charAt(i) != expected) {
                char found = i >= line.length() ? '?' : line.charAt(i);
                throw fail(code, detail + " (found '" + found + "')", lineNo, sourceLine);
            }
            i++;
        }

        private char readChar(DbcDiagnosticCode code, String what) throws DbcParseException {
            if (i >= line.length()) {
                throw fail(code, "Unexpected end of line while reading " + what, lineNo, sourceLine);
            }
            return line.charAt(i++);
        }

        private String readIdentifier(DbcDiagnosticCode code, String what) throws DbcParseException {
            if (i >= line.length()) {
                throw fail(code, "Missing " + what, lineNo, sourceLine);
            }
            int start = i;
            char first = line.charAt(i);
            if (!(Character.isLetter(first) || first == '_')) {
                throw fail(code, "Invalid " + what + " start '" + first + "'", lineNo, sourceLine);
            }
            i++;
            while (i < line.length()) {
                char ch = line.charAt(i);
                if (Character.isLetterOrDigit(ch) || ch == '_') {
                    i++;
                } else {
                    break;
                }
            }
            return line.substring(start, i);
        }

        private MuxToken tryReadMux() throws DbcParseException {
            if (i >= line.length()) {
                return null;
            }
            // Mux tokens appear before ':' and are never the colon itself.
            if (line.charAt(i) == ':') {
                return null;
            }
            int save = i;
            if (line.charAt(i) == 'M') {
                int next = i + 1;
                if (next >= line.length() || Character.isWhitespace(line.charAt(next)) || line.charAt(next) == ':') {
                    i = next;
                    return new MuxToken(DbcMultiplexRole.MULTIPLEXOR, null, false);
                }
                // Not a bare M multiplexor (e.g. part of something else) — restore.
                i = save;
                return null;
            }
            if (line.charAt(i) == 'm') {
                int j = i + 1;
                if (j >= line.length() || !Character.isDigit(line.charAt(j))) {
                    i = save;
                    return null;
                }
                while (j < line.length() && Character.isDigit(line.charAt(j))) {
                    j++;
                }
                boolean extended = false;
                if (j < line.length() && line.charAt(j) == 'M') {
                    extended = true;
                    j++;
                }
                // Must be followed by whitespace or ':'
                if (j < line.length() && !(Character.isWhitespace(line.charAt(j)) || line.charAt(j) == ':')) {
                    i = save;
                    return null;
                }
                String digits = line.substring(i + 1, extended ? j - 1 : j);
                int value;
                try {
                    value = Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    throw fail(DbcDiagnosticCode.DBC_SG_INVALID_MULTIPLEX,
                            "Invalid multiplex value '" + digits + "'", lineNo, sourceLine);
                }
                i = j;
                // Extended form mNM: multiplexed with value N; trailing M marks extended multiplexor.
                return new MuxToken(DbcMultiplexRole.MULTIPLEXED, value, extended);
            }
            // If we see an identifier here, the caller already consumed the signal name; anything else
            // before ':' is an invalid multiplex token.
            if (!Character.isWhitespace(line.charAt(i)) && line.charAt(i) != ':') {
                String bad = readIdentifier(DbcDiagnosticCode.DBC_SG_INVALID_MULTIPLEX, "multiplex token");
                throw fail(DbcDiagnosticCode.DBC_SG_INVALID_MULTIPLEX,
                        "Unsupported multiplex token '" + bad + "'", lineNo, sourceLine);
            }
            return null;
        }

        private int readUnsignedInt(DbcDiagnosticCode code, String what) throws DbcParseException {
            int start = i;
            while (i < line.length() && Character.isDigit(line.charAt(i))) {
                i++;
            }
            if (start == i) {
                throw fail(code, "Missing " + what, lineNo, sourceLine);
            }
            return DbcNumberParser.parseInt(line.substring(start, i), code, "for " + what);
        }

        private String readUntil(char end, DbcDiagnosticCode code, String what) throws DbcParseException {
            int start = i;
            while (i < line.length() && line.charAt(i) != end) {
                i++;
            }
            if (i >= line.length()) {
                throw fail(code, "Unterminated " + what + " (missing '" + end + "')", lineNo, sourceLine);
            }
            return line.substring(start, i).trim();
        }

        private String readQuotedUnit() throws DbcParseException {
            if (i >= line.length() || line.charAt(i) != '"') {
                throw fail(DbcDiagnosticCode.DBC_SG_UNTERMINATED_UNIT,
                        "expected quoted unit", lineNo, sourceLine);
            }
            i++; // opening quote
            int start = i;
            while (i < line.length() && line.charAt(i) != '"') {
                i++;
            }
            if (i >= line.length()) {
                throw fail(DbcDiagnosticCode.DBC_SG_UNTERMINATED_UNIT,
                        "Unterminated unit string", lineNo, sourceLine);
            }
            String unit = line.substring(start, i);
            i++; // closing quote
            return unit;
        }

        private List<String> readReceivers() throws DbcParseException {
            skipWs();
            if (i >= line.length()) {
                return List.of();
            }
            String raw = line.substring(i).trim();
            i = line.length();
            if (raw.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            // DBC receivers are comma-separated; also accept whitespace-separated single tokens.
            if (raw.contains(",")) {
                for (String part : raw.split(",")) {
                    addReceiver(out, part.trim());
                }
            } else {
                for (String part : raw.split("\\s+")) {
                    addReceiver(out, part.trim());
                }
            }
            return List.copyOf(out);
        }

        private void addReceiver(List<String> out, String token) throws DbcParseException {
            if (token.isEmpty()) {
                return;
            }
            if (!isValidReceiver(token)) {
                throw fail(DbcDiagnosticCode.DBC_SG_INVALID_RECEIVER,
                        "Invalid receiver '" + token + "'", lineNo, sourceLine);
            }
            out.add(token);
        }

        private static boolean isValidReceiver(String token) {
            if (token.isEmpty()) {
                return false;
            }
            char first = token.charAt(0);
            if (!(Character.isLetter(first) || first == '_')) {
                return false;
            }
            for (int k = 1; k < token.length(); k++) {
                char ch = token.charAt(k);
                if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                    return false;
                }
            }
            return true;
        }
    }
}
