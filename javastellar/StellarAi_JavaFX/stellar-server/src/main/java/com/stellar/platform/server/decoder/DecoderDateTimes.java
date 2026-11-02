package com.stellar.platform.server.decoder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats a source recording clock as {@code yyyy-MM-dd HH:mm:ss.SSS}.
 * <p>
 * The formatter uses {@code uuuu} and {@link Locale#ROOT}. {@code SSS} is the
 * millisecond field: nanoseconds inside that millisecond are discarded, not rounded.
 * No zone is applied and no current-time value is used.
 */
final class DecoderDateTimes {

    static final String FORMAT_TEXT = "yyyy-MM-dd HH:mm:ss.SSS";

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private DecoderDateTimes() {
    }

    static String format(LocalDateTime value) {
        return value == null ? null : FORMAT.format(value);
    }

    /**
     * {@code AVAILABLE} when every signal that has samples has a recording origin,
     * {@code UNAVAILABLE} when none do, and {@code PARTIAL} when the selected
     * signals come from sources with different timing availability.
     */
    static String availability(int signalsWithSamples, int signalsWithClock) {
        if (signalsWithSamples <= 0 || signalsWithClock <= 0) {
            return "UNAVAILABLE";
        }
        if (signalsWithClock == signalsWithSamples) {
            return "AVAILABLE";
        }
        return "PARTIAL";
    }
}
