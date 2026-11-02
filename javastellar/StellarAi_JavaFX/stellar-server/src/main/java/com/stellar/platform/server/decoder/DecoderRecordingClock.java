package com.stellar.platform.server.decoder;

import com.stellar.platform.parser.mf4.schema.Mdf4RecordingStart;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.map.time.TimestampObservation;
import com.stellar.platform.processing.map.time.TimestampRawKind;
import com.stellar.platform.processing.map.time.TimestampSource;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Source recording clock for one measurement file.
 * Relative samples are added to the civil start stored in the file.
 * An MF4 UTC count uses the file's own timezone and DST minutes when that flag is valid.
 */
final class DecoderRecordingClock {

    record Read(SourceClock clock, String reason) {
    }

    private DecoderRecordingClock() {
    }

    static Read read(java.nio.file.Path measurement, String format) {
        TimestampObservation observation = RecordingClockReader.observe(measurement);
        if (observation.source() == TimestampSource.MF4_HD) {
            return mf4(measurement);
        }
        if (observation.source() == TimestampSource.MDF3_HD) {
            return fromObservation(observation);
        }
        String name = measurement.getFileName() == null ? "" : measurement.getFileName().toString();
        if (isMf4(format) || isMf4(name)) {
            return mf4(measurement);
        }
        return fromObservation(observation);
    }

    static String describe(Map<String, String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "recording time is not available";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String reason : reasons.values()) {
            if (reason != null && !reason.isBlank()) {
                unique.add(reason);
            }
        }
        if (unique.isEmpty()) {
            return "recording time is not available";
        }
        return String.join("; ", unique);
    }

    private static Read mf4(java.nio.file.Path measurement) {
        return Mdf4RecordingStart.sourceHeader(measurement)
                .flatMap(DecoderRecordingClock::displayStart)
                .map(start -> new Read(SourceClock.civil(start), "MF4 header start time"))
                .orElse(new Read(SourceClock.missing(), "MF4 recording start is not available"));
    }

    /**
     * A UTC count with a valid offset is shown at that offset, matching the MDF4 rule
     * that local time is the UTC count plus timezone and DST minutes.
     * A local-time count is already that clock, so the offset is not added again.
     * An invalid offset flag leaves the UTC count unchanged.
     */
    private static Optional<LocalDateTime> displayStart(Mdf4RecordingStart.SourceHeader header) {
        try {
            LocalDateTime digits = clockDigits(header.startNanos());
            if (!header.localTime() && header.offsetsValid()) {
                digits = digits.plusMinutes((long) header.tzOffsetMin() + header.dstOffsetMin());
            }
            return Optional.of(digits);
        } catch (DateTimeException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    /**
     * Prints the stored nanosecond count as calendar fields.
     * For a UTC header those fields are UTC. For a local header they are the stored local clock.
     */
    private static LocalDateTime clockDigits(long count) {
        long seconds = Math.floorDiv(count, 1_000_000_000L);
        int nanos = (int) Math.floorMod(count, 1_000_000_000L);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
    }

    private static Read fromObservation(TimestampObservation observation) {
        if (observation.rawKind() == TimestampRawKind.CIVIL_LOCAL && observation.civilLocal() != null) {
            return new Read(SourceClock.civil(observation.civilLocal()), civilReason(observation));
        }
        if (observation.rawKind() == TimestampRawKind.ISO_ZONED && observation.offsetMinutes() != null) {
            return new Read(SourceClock.absolute(observation.offsetMinutes()), "ISO timestamp");
        }
        if (observation.rawKind() == TimestampRawKind.ISO_UNZONED) {
            return new Read(SourceClock.absolute(0), "ISO timestamp");
        }
        String detail = observation.detail();
        return new Read(SourceClock.missing(), detail == null || detail.isBlank()
                ? "recording time is not available" : detail);
    }

    private static String civilReason(TimestampObservation observation) {
        return switch (observation.source()) {
            case ASC_DATE -> "ASC date line";
            case BLF_HEADER -> "BLF SYSTEMTIME";
            case MDF3_HD -> "MDF 3 HD date and time";
            default -> observation.detail() == null || observation.detail().isBlank()
                    ? "recording time established" : observation.detail();
        };
    }

    private static boolean isMf4(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return "mf4".equals(lower) || "mdf".equals(lower) || lower.endsWith(".mf4") || lower.endsWith(".mdf");
    }
}
