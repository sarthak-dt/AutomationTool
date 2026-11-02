package com.stellar.platform.desktop.ui.graph.map;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats measurement timestamps for the map and its timeline.
 *
 * <p>Measurements may carry absolute epoch nanoseconds or a relative recording clock. Both are
 * formatted here rather than in view code, so the map and the timeline never disagree.
 */
public final class MapTimeFormat {

    /** Epoch nanos below this (2001-09-09) are treated as a relative recording clock. */
    private static final long ABSOLUTE_THRESHOLD_NANOS = 1_000_000_000_000_000_000L;

    private static final DateTimeFormatter CLOCK_MILLIS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private MapTimeFormat() {
    }

    public static boolean absolute(long timestampNanos) {
        return timestampNanos >= ABSOLUTE_THRESHOLD_NANOS;
    }

    public static String withMillis(long timestampNanos) {
        if (!absolute(timestampNanos)) {
            return String.format("%.3f s", timestampNanos / 1e9);
        }
        return CLOCK_MILLIS.format(instant(timestampNanos));
    }

    public static String clock(long timestampNanos) {
        if (!absolute(timestampNanos)) {
            return String.format("%.0f s", timestampNanos / 1e9);
        }
        return CLOCK.format(instant(timestampNanos));
    }

    /** Human duration such as {@code 23m 35.546s}, matching the range summary in the design. */
    public static String duration(long durationNanos) {
        long totalMillis = Math.abs(durationNanos) / 1_000_000L;
        long hours = totalMillis / 3_600_000L;
        long minutes = (totalMillis % 3_600_000L) / 60_000L;
        double seconds = (totalMillis % 60_000L) / 1000.0;
        if (hours > 0) {
            return String.format("%dh %02dm %05.3fs", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %06.3fs", minutes, seconds);
        }
        return String.format("%.3fs", seconds);
    }

    private static java.time.LocalDateTime instant(long timestampNanos) {
        return Instant.ofEpochSecond(
                        Math.floorDiv(timestampNanos, 1_000_000_000L),
                        Math.floorMod(timestampNanos, 1_000_000_000L))
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
