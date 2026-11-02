package com.stellar.platform.processing.map.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a text time column. A name such as {@code GPS_Time} or {@code Epoch} is not evidence.
 * An ISO value is absolute only when the source text itself contains {@code Z} or a numeric offset.
 */
final class TextRecordingStartAdapter {

    private static final Pattern ISO_ZONED = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_BARE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

    private TextRecordingStartAdapter() {
    }

    static TimestampObservation read(String measurementId, String text) {
        if (text == null || text.isBlank()) {
            return TimestampObservation.none(measurementId, "text measurement is empty");
        }
        String[] lines = text.split("\\R");
        List<String> usable = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                usable.add(line.trim());
            }
        }
        if (usable.size() < 2) {
            return TimestampObservation.none(measurementId, "text measurement has no time rows");
        }
        if (structured(usable.getFirst())) {
            return new TimestampObservation(
                    measurementId, TimestampSource.TEXT_COLUMN, TimestampRawKind.ELAPSED, unitOfStructured(usable.getFirst()),
                    null, false, false, 0L, null, "structured text time is elapsed");
        }
        char delimiter = delimiter(usable.getFirst());
        boolean whitespace = delimiter == 0;
        String[] header = split(usable.getFirst(), delimiter, whitespace);
        int timeColumn = timeColumn(header);
        if (timeColumn < 0) {
            return TimestampObservation.none(measurementId, "no accepted time column");
        }
        TimeUnitKind unit = unitOf(header[timeColumn]);
        List<String> values = new ArrayList<>();
        for (int i = 1; i < usable.size() && values.size() < 8; i++) {
            String[] cells = split(usable.get(i), delimiter, whitespace);
            if (timeColumn < cells.length && !cells[timeColumn].isBlank()) {
                values.add(cells[timeColumn].trim());
            }
        }
        if (values.isEmpty()) {
            return TimestampObservation.none(measurementId, "time column has no values");
        }
        if (values.stream().allMatch(value -> ISO_ZONED.matcher(value).matches())) {
            return zoned(measurementId, values.getFirst());
        }
        if (values.stream().allMatch(value -> ISO_BARE.matcher(value).matches())) {
            return unzoned(measurementId, values.getFirst());
        }
        if (unit != TimeUnitKind.NONE && values.stream().allMatch(value -> NUMBER.matcher(value).matches())) {
            return new TimestampObservation(
                    measurementId, TimestampSource.TEXT_COLUMN, TimestampRawKind.ELAPSED, unit,
                    null, false, false, 0L, null, "text time column is elapsed");
        }
        return TimestampObservation.none(measurementId, "time column is not a supported clock");
    }

    private static TimestampObservation zoned(String measurementId, String raw) {
        String normalized = raw.replace(' ', 'T');
        try {
            Instant instant = Instant.parse(normalized);
            long nanos = Math.addExact(
                    Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
            return new TimestampObservation(
                    measurementId, TimestampSource.TEXT_ISO, TimestampRawKind.ISO_ZONED, TimeUnitKind.NANOSECONDS,
                    offsetMinutes(normalized), true, false, nanos, null, "ISO timestamp includes a timezone");
        } catch (DateTimeParseException | ArithmeticException ex) {
            return TimestampObservation.none(measurementId, "zoned ISO timestamp could not be parsed");
        }
    }

    private static int offsetMinutes(String normalized) {
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            return 0;
        }
        int signIndex = Math.max(normalized.lastIndexOf('+'), normalized.lastIndexOf('-'));
        String token = signIndex >= 0 ? normalized.substring(signIndex) : "";
        String digits = token.replace(":", "");
        if (digits.length() != 5) {
            return 0;
        }
        int minutes = Integer.parseInt(digits.substring(1, 3)) * 60 + Integer.parseInt(digits.substring(3, 5));
        return digits.charAt(0) == '-' ? -minutes : minutes;
    }

    private static TimestampObservation unzoned(String measurementId, String raw) {
        String normalized = raw.replace(' ', 'T');
        try {
            LocalDateTime civil = LocalDateTime.parse(normalized);
            return new TimestampObservation(
                    measurementId, TimestampSource.TEXT_ISO, TimestampRawKind.ISO_UNZONED, TimeUnitKind.NONE,
                    null, false, true, 0L, civil, "ISO timestamp has no timezone");
        } catch (DateTimeException ex) {
            return TimestampObservation.none(measurementId, "unzoned ISO timestamp could not be parsed");
        }
    }

    /**
     * Only names the existing text reader already treats as a time axis.
     * {@code GPS_Time}, {@code Epoch}, {@code DateTime}, and {@code UTC_Time} are not accepted.
     */
    private static int timeColumn(String[] header) {
        for (int i = 0; i < header.length; i++) {
            String name = normalize(header[i]);
            if (name.equals("time") || name.equals("timestamp") || suffixUnit(name) != TimeUnitKind.NONE) {
                return i;
            }
        }
        return -1;
    }

    private static TimeUnitKind unitOf(String headerCell) {
        String name = normalize(headerCell);
        TimeUnitKind fromName = suffixUnit(name);
        if (fromName != TimeUnitKind.NONE) {
            return fromName;
        }
        String unit = parenthetical(headerCell);
        return switch (unit) {
            case "ns" -> TimeUnitKind.NANOSECONDS;
            case "us", "µs" -> TimeUnitKind.MICROSECONDS;
            case "ms" -> TimeUnitKind.MILLISECONDS;
            case "s", "sec", "seconds" -> TimeUnitKind.SECONDS;
            default -> TimeUnitKind.NONE;
        };
    }

    private static TimeUnitKind suffixUnit(String name) {
        if (!(name.startsWith("time") || name.startsWith("timestamp"))) {
            return TimeUnitKind.NONE;
        }
        if (name.endsWith("_ns")) {
            return TimeUnitKind.NANOSECONDS;
        }
        if (name.endsWith("_us")) {
            return TimeUnitKind.MICROSECONDS;
        }
        if (name.endsWith("_ms")) {
            return TimeUnitKind.MILLISECONDS;
        }
        if (name.endsWith("_s")) {
            return TimeUnitKind.SECONDS;
        }
        return TimeUnitKind.NONE;
    }

    private static String parenthetical(String raw) {
        String text = raw == null ? "" : raw.trim();
        int open = Math.max(text.lastIndexOf('('), text.lastIndexOf('['));
        if (open > 0 && (text.endsWith(")") || text.endsWith("]"))) {
            return text.substring(open + 1, text.length() - 1).trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static String normalize(String raw) {
        String text = parenthetical(raw).isEmpty() ? raw : raw.substring(0, Math.max(raw.lastIndexOf('('), raw.lastIndexOf('[')));
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean structured(String line) {
        return line.matches("(?i)[+-]?\\d+(?:\\.\\d+)?(?:ns|us|ms|s)\\s+.+");
    }

    private static TimeUnitKind unitOfStructured(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("ns")) {
            return TimeUnitKind.NANOSECONDS;
        }
        if (lower.contains("us")) {
            return TimeUnitKind.MICROSECONDS;
        }
        if (lower.contains("ms")) {
            return TimeUnitKind.MILLISECONDS;
        }
        return TimeUnitKind.SECONDS;
    }

    private static char delimiter(String header) {
        if (header.indexOf('\t') >= 0) {
            return '\t';
        }
        if (header.indexOf(';') >= 0) {
            return ';';
        }
        if (header.indexOf(',') >= 0) {
            return ',';
        }
        return 0;
    }

    private static String[] split(String line, char delimiter, boolean whitespace) {
        if (whitespace) {
            return line.trim().split("\\s+");
        }
        return line.split(Pattern.quote(String.valueOf(delimiter)), -1);
    }
}
