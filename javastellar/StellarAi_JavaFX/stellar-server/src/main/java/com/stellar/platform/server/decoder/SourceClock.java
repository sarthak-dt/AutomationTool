package com.stellar.platform.server.decoder;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Recording clock taken from a measurement file, before a sample offset is added.
 * A civil start is added to each relative sample. An absolute sample is already a
 * complete instant and is printed at the offset stored with that timestamp.
 */
final class SourceClock {

    private final LocalDateTime civilStart;
    private final Integer absoluteOffsetMinutes;

    private SourceClock(LocalDateTime civilStart, Integer absoluteOffsetMinutes) {
        this.civilStart = civilStart;
        this.absoluteOffsetMinutes = absoluteOffsetMinutes;
    }

    static SourceClock civil(LocalDateTime start) {
        return new SourceClock(start, null);
    }

    static SourceClock absolute(int offsetMinutes) {
        return new SourceClock(null, offsetMinutes);
    }

    static SourceClock missing() {
        return new SourceClock(null, null);
    }

    boolean available() {
        return civilStart != null || absoluteOffsetMinutes != null;
    }

    String format(long sampleNanos) {
        try {
            LocalDateTime value;
            if (civilStart != null) {
                value = civilStart.plusNanos(sampleNanos);
            } else if (absoluteOffsetMinutes != null) {
                long seconds = Math.floorDiv(sampleNanos, 1_000_000_000L);
                int nanos = (int) Math.floorMod(sampleNanos, 1_000_000_000L);
                value = Instant.ofEpochSecond(seconds, nanos)
                        .atOffset(ZoneOffset.ofTotalSeconds(absoluteOffsetMinutes * 60))
                        .toLocalDateTime();
            } else {
                return null;
            }
            return DecoderDateTimes.format(value);
        } catch (DateTimeException | ArithmeticException ex) {
            return null;
        }
    }
}
