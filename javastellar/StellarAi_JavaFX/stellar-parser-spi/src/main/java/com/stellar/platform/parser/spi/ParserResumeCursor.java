package com.stellar.platform.parser.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Parser-owned resume position. Storage persists {@link #serialize()} opaquely and never
 * interprets MF4 internals.
 *
 * <p>Not Java serialization. Format is three newline-separated fields:
 * {@code parserType}, {@code cursorVersion}, {@code payload}.</p>
 */
public record ParserResumeCursor(String parserType, int cursorVersion, String payload) {

    public static final String TYPE_MF4 = "mf4";
    public static final int VERSION_1 = 1;

    public ParserResumeCursor {
        parserType = parserType == null ? "" : parserType.trim();
        payload = payload == null ? "" : payload;
        if (parserType.isEmpty()) {
            throw new IllegalArgumentException("parserType must not be blank");
        }
        if (cursorVersion < 1) {
            throw new IllegalArgumentException("cursorVersion must be >= 1");
        }
    }

    public String serialize() {
        return parserType + "\n" + cursorVersion + "\n" + payload;
    }

    /**
     * @return empty when the stored string is blank, truncated, or has a non-positive version
     */
    public static Optional<ParserResumeCursor> deserialize(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        int first = stored.indexOf('\n');
        if (first <= 0) {
            return Optional.empty();
        }
        int second = stored.indexOf('\n', first + 1);
        if (second < 0) {
            return Optional.empty();
        }
        String type = stored.substring(0, first).trim();
        String versionText = stored.substring(first + 1, second).trim();
        String body = stored.substring(second + 1);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        int version;
        try {
            version = Integer.parseInt(versionText);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (version < 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParserResumeCursor(type, version, body));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean isMf4V1() {
        return TYPE_MF4.equalsIgnoreCase(parserType) && cursorVersion == VERSION_1;
    }

    public static ParserResumeCursor mf4(String payload) {
        return new ParserResumeCursor(TYPE_MF4, VERSION_1, Objects.requireNonNullElse(payload, ""));
    }
}
