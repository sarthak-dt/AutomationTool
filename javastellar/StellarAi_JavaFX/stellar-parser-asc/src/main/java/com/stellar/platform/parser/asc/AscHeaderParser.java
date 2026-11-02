package com.stellar.platform.parser.asc;

import com.stellar.platform.parser.spi.ParserException;

import java.util.Locale;

/**
 * ASC header lines. Unknown {@code base} values fail the file because later numeric
 * interpretation would be unsafe.
 */
final class AscHeaderParser {

    private AscHeaderParser() {
    }

    static boolean handle(String line, AscParseSession session) throws ParserException {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("date ")) {
            String rest = line.substring(5).trim();
            session.dateHeader(rest);
            session.timezonePresent(containsTimezone(rest));
            return true;
        }
        if (lower.startsWith("base ")) {
            parseBaseLine(line, lower, session);
            return true;
        }
        if (lower.equals("internal events logged")) {
            session.internalEventsLogged(true);
            return true;
        }
        if (lower.equals("no internal events logged")) {
            session.internalEventsLogged(false);
            return true;
        }
        if (lower.startsWith("begin triggerblock") || lower.startsWith("end triggerblock")) {
            return true;
        }
        if (lower.contains("start of measurement")) {
            return true;
        }
        if (lower.startsWith("//") || lower.startsWith("*")) {
            return true;
        }
        if (lower.startsWith("version") || lower.startsWith("// version")) {
            return true;
        }
        return false;
    }

    static boolean isIgnorableNonFrame(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("errorframe")
                || lower.startsWith("statistic:")
                || lower.contains(" trigger ")
                || lower.startsWith("end trigger")
                || lower.startsWith("begin trigger");
    }

    private static void parseBaseLine(String line, String lower, AscParseSession session) throws ParserException {
        if (session.emittedFrame() && session.baseDeclared()) {
            throw new ParserException(
                    "Invalid ASC header/base: base declared after CAN frames were already parsed (" + line + ")");
        }
        if (session.emittedFrame()) {
            throw new ParserException(
                    "Invalid ASC header/base: base appeared after CAN frames, numeric interpretation is unsafe");
        }
        String[] tokens = new String[AscTokenizer.MAX_TOKENS];
        int n = AscTokenizer.tokenize(lower, tokens);
        AscNumericBase base = null;
        AscTimestampMode mode = session.timestampMode();
        for (int i = 1; i < n; i++) {
            String token = tokens[i];
            if ("hex".equals(token)) {
                base = AscNumericBase.HEX;
            } else if ("dec".equals(token) || "decimal".equals(token)) {
                base = AscNumericBase.DEC;
            } else if ("absolute".equals(token)) {
                mode = AscTimestampMode.ABSOLUTE;
            } else if ("relative".equals(token)) {
                mode = AscTimestampMode.RELATIVE;
            } else if ("timestamps".equals(token) || "timestamp".equals(token)) {
                continue;
            } else {
                throw new ParserException(
                        "Invalid ASC header/base: unsupported token '" + token + "' in: " + line);
            }
        }
        if (base == null) {
            throw new ParserException("Invalid ASC header/base: missing hex/dec in: " + line);
        }
        session.applyBase(base);
        session.timestampMode(mode);
    }

    private static boolean containsTimezone(String date) {
        String lower = date.toLowerCase(Locale.ROOT);
        return lower.contains("gmt")
                || lower.contains("utc")
                || lower.contains("cst")
                || lower.contains("est")
                || lower.contains("pst")
                || lower.contains("cet")
                || lower.contains("cest");
    }
}
