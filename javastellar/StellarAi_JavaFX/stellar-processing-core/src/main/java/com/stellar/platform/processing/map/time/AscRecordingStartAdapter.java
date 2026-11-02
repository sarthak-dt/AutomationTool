package com.stellar.platform.processing.map.time;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ASC {@code date} line. Frame seconds stay elapsed in the sample clock
 * and are not a recording start. A timezone word is not an offset.
 */
final class AscRecordingStartAdapter {

    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d{2}):?(\\d{2})\\s*$");
    private static final DateTimeFormatter[] FORMATS = {
            DateTimeFormatter.ofPattern("EEE MMM dd hh:mm:ss.SSS a yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM d hh:mm:ss.SSS a yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss.SSS yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss.SSS yyyy", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)
    };

    private AscRecordingStartAdapter() {
    }

    static TimestampObservation read(String measurementId, String text) {
        String dateLine = dateLine(text);
        if (dateLine == null) {
            return new TimestampObservation(
                    measurementId, TimestampSource.ASC_DATE, TimestampRawKind.ELAPSED, TimeUnitKind.SECONDS,
                    null, false, false, 0L, null,
                    "ASC timestamps are seconds from the logging start");
        }
        String body = dateLine.substring(5).trim();
        Integer offset = null;
        boolean zoneExplicit = false;
        Matcher matcher = OFFSET.matcher(body);
        if (matcher.find()) {
            int minutes = Integer.parseInt(matcher.group(2)) * 60 + Integer.parseInt(matcher.group(3));
            if ("-".equals(matcher.group(1))) {
                minutes = -minutes;
            }
            offset = minutes;
            zoneExplicit = true;
            body = body.substring(0, matcher.start()).trim();
        }
        body = body.replaceAll("(?i)\\s+(?:utc|gmt|cst|est|pst|cet|cest)\\s*$", "").trim();
        body = body.replaceAll("(?i)\\bam\\b", "AM").replaceAll("(?i)\\bpm\\b", "PM");
        LocalDateTime civil = parse(body);
        if (civil == null) {
            return TimestampObservation.none(measurementId, "ASC date could not be parsed");
        }
        String detail = zoneExplicit
                ? "ASC date includes a numeric offset"
                : "ASC date is local time without a timezone";
        return new TimestampObservation(
                measurementId, TimestampSource.ASC_DATE, TimestampRawKind.CIVIL_LOCAL, TimeUnitKind.NONE,
                offset, zoneExplicit, true, 0L, civil, detail);
    }

    private static String dateLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String raw : text.split("\\R", 40)) {
            String line = raw.trim();
            if (line.length() >= 5 && line.regionMatches(true, 0, "date ", 0, 5)) {
                return line;
            }
        }
        return null;
    }

    private static LocalDateTime parse(String body) {
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDateTime.parse(body, format);
            } catch (DateTimeParseException ignored) {
                // try the next Vector date layout
            }
        }
        return null;
    }
}
