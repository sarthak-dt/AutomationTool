package com.stellar.platform.parser.asc;

/**
 * Locale-independent ASC timestamp conversion.
 * <p>
 * Canonical contract: {@code CanonicalCanFrame.timestampNanos} is nanoseconds.
 * ASC timestamps are decimal seconds (relative to the logging start in both
 * {@code timestamps absolute} and {@code timestamps relative} header modes).
 * They are <em>not</em> converted to wall-clock time.
 * <p>
 * {@code 0.123456} → {@code 123_456_000} ns. Fractional digits beyond nanoseconds
 * are truncated, not rounded, to keep conversion deterministic.
 */
final class AscTimestampCodec {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_SECONDS = Long.MAX_VALUE / NANOS_PER_SECOND;

    private AscTimestampCodec() {
    }

    static long secondsTokenToNanos(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty timestamp");
        }
        int length = token.length();
        int dot = -1;
        for (int i = 0; i < length; i++) {
            char c = token.charAt(i);
            if (c == '.') {
                if (dot >= 0) {
                    throw new IllegalArgumentException("invalid timestamp");
                }
                dot = i;
            } else if (c < '0' || c > '9') {
                throw new IllegalArgumentException("invalid timestamp");
            }
        }
        String secondsPart = dot < 0 ? token : token.substring(0, dot);
        String fractionPart = dot < 0 ? "" : token.substring(dot + 1);
        if (secondsPart.isEmpty()) {
            secondsPart = "0";
        }
        long seconds = parseUnsignedDecimal(secondsPart);
        if (seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("timestamp overflow");
        }
        long nanos = fractionToNanos(fractionPart);
        return seconds * NANOS_PER_SECOND + nanos;
    }

    private static long fractionToNanos(String fraction) {
        if (fraction.isEmpty()) {
            return 0L;
        }
        char[] digits = new char[9];
        int copy = Math.min(9, fraction.length());
        fraction.getChars(0, copy, digits, 0);
        for (int i = copy; i < 9; i++) {
            digits[i] = '0';
        }
        return parseUnsignedDecimal(new String(digits));
    }

    private static long parseUnsignedDecimal(String digits) {
        long value = 0L;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("invalid timestamp");
            }
            if (value > (Long.MAX_VALUE - d) / 10L) {
                throw new IllegalArgumentException("timestamp overflow");
            }
            value = value * 10L + d;
        }
        return value;
    }
}
