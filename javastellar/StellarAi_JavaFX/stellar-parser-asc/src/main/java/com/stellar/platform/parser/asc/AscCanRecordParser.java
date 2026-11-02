package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;

/**
 * Classical CAN data-frame records.
 * <p>
 * Typical: {@code <time> <channel> <id>[x] <Rx|Tx> d <dlc> <b0> ...}
 * Extended IDs use a trailing {@code x} on the ID token or a following {@code x} token.
 */
final class AscCanRecordParser {

    static final int CLASSIC_MAX_DLC = 8;

    private AscCanRecordParser() {
    }

    static CanonicalCanFrame parseClassic(
            String[] tokens,
            int tokenCount,
            String line,
            long lineNumber,
            long sequenceNumber,
            AscNumericBase base
    ) {
        if (tokenCount < 5) {
            throw new IllegalArgumentException("insufficient tokens");
        }
        long timestampNanos = AscTimestampCodec.secondsTokenToNanos(tokens[0]);
        int channel = parseDecimalInt(tokens[1], "channel");
        if (channel < 0) {
            throw new IllegalArgumentException("invalid channel");
        }
        int index = 2;
        IdParse id = parseCanId(tokens, tokenCount, index, base);
        index = id.nextIndex;
        if (index >= tokenCount) {
            throw new IllegalArgumentException("missing direction");
        }
        FrameDirection direction = parseDirection(tokens[index]);
        index++;
        if (index >= tokenCount) {
            throw new IllegalArgumentException("missing frame type");
        }
        String frameType = tokens[index];
        index++;
        if (!"d".equalsIgnoreCase(frameType)) {
            throw new UnsupportedFrameException(
                    "Unsupported Classical CAN frame type '" + frameType
                            + "' (only data frames 'd' are supported)");
        }
        if (index >= tokenCount) {
            throw new IllegalArgumentException("missing DLC");
        }
        int dlc = parseDecimalInt(tokens[index], "DLC");
        index++;
        if (dlc < 0 || dlc > CLASSIC_MAX_DLC) {
            throw new IllegalArgumentException("DLC out of Classical CAN range 0..8: " + dlc);
        }
        byte[] data = new byte[dlc];
        int radix = base == AscNumericBase.DEC ? 10 : 16;
        for (int i = 0; i < dlc; i++) {
            if (index + i >= tokenCount) {
                throw new IllegalArgumentException("payload shorter than DLC");
            }
            data[i] = parseByte(tokens[index + i], radix);
        }
        return new CanonicalCanFrame(
                sequenceNumber,
                timestampNanos,
                channel,
                id.canId,
                id.extended,
                direction,
                dlc,
                data,
                FrameQuality.OK,
                SourceReference.ofLine(lineNumber)
        );
    }

    static boolean looksLikeFrameStart(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        return first >= '0' && first <= '9';
    }

    static boolean containsIgnoreCase(String[] tokens, int tokenCount, String needle) {
        for (int i = 0; i < tokenCount; i++) {
            if (needle.equalsIgnoreCase(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    private static IdParse parseCanId(String[] tokens, int tokenCount, int index, AscNumericBase base) {
        if (index >= tokenCount) {
            throw new IllegalArgumentException("missing CAN ID");
        }
        String idToken = tokens[index];
        boolean extendedSuffix = false;
        if (idToken.length() > 1 && (idToken.endsWith("x") || idToken.endsWith("X"))) {
            extendedSuffix = true;
            idToken = idToken.substring(0, idToken.length() - 1);
        }
        long canId = parseId(idToken, base);
        int next = index + 1;
        if (!extendedSuffix && next < tokenCount && "x".equalsIgnoreCase(tokens[next])) {
            extendedSuffix = true;
            next++;
        }
        boolean extended = extendedSuffix || canId > 0x7FFL;
        if (extended && canId > 0x1FFFFFFFL) {
            throw new IllegalArgumentException("extended CAN ID out of range");
        }
        return new IdParse(canId, extended, next);
    }

    private static long parseId(String raw, AscNumericBase base) {
        String token = raw;
        if (token.regionMatches(true, 0, "0x", 0, 2)) {
            token = token.substring(2);
            return parseUnsigned(token, 16, "CAN ID");
        }
        int radix = base == AscNumericBase.DEC ? 10 : 16;
        return parseUnsigned(token, radix, "CAN ID");
    }

    private static FrameDirection parseDirection(String token) {
        if ("Rx".equalsIgnoreCase(token) || "R".equalsIgnoreCase(token)) {
            return FrameDirection.RX;
        }
        if ("Tx".equalsIgnoreCase(token) || "T".equalsIgnoreCase(token)) {
            return FrameDirection.TX;
        }
        throw new IllegalArgumentException("invalid direction: " + token);
    }

    private static int parseDecimalInt(String token, String field) {
        long value = parseUnsigned(token, 10, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " overflow");
        }
        return (int) value;
    }

    private static byte parseByte(String token, int radix) {
        long value = parseUnsigned(token, radix, "payload");
        if (value > 0xFFL) {
            throw new IllegalArgumentException("payload byte out of range");
        }
        return (byte) value;
    }

    private static long parseUnsigned(String token, int radix, String field) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty " + field);
        }
        try {
            return Long.parseLong(token, radix);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    private record IdParse(long canId, boolean extended, int nextIndex) {
    }

    static final class UnsupportedFrameException extends RuntimeException {
        UnsupportedFrameException(String message) {
            super(message);
        }
    }
}
