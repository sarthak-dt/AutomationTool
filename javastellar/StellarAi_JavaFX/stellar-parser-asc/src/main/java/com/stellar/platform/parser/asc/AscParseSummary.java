package com.stellar.platform.parser.asc;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded end-of-parse summary. Logged once per file, never per frame.
 */
final class AscParseSummary {

    private final String fileName;
    private final long validFrames;
    private final long skippedNonData;
    private final long malformed;
    private final long unsupported;
    private final int channels;
    private final Set<Integer> channelIds;
    private final long firstTimestampNanos;
    private final long lastTimestampNanos;
    private final long timestampRegressions;
    private final String dateHeader;
    private final boolean timezonePresent;
    private final AscNumericBase base;
    private final AscTimestampMode timestampMode;
    private final Map<String, Integer> warningCounts;

    AscParseSummary(
            String fileName,
            AscParseSession session,
            AscDiagnostics diagnostics
    ) {
        this.fileName = fileName == null ? "" : fileName;
        this.validFrames = session.validFrames();
        this.skippedNonData = session.skippedNonData();
        this.malformed = diagnostics.malformed();
        this.unsupported = diagnostics.skippedNonData();
        this.channels = session.channelCount();
        this.channelIds = session.channels();
        this.firstTimestampNanos = session.firstTimestampNanos();
        this.lastTimestampNanos = session.lastTimestampNanos();
        this.timestampRegressions = session.timestampRegressions();
        this.dateHeader = session.dateHeader();
        this.timezonePresent = session.timezonePresent();
        this.base = session.baseOrDefault();
        this.timestampMode = session.timestampMode();
        this.warningCounts = diagnostics.counts();
    }

    String fileName() {
        return fileName;
    }

    long validFrames() {
        return validFrames;
    }

    String formatLogLine() {
        return "ASC Parsing Summary"
                + " File: " + fileName
                + " Valid CAN frames: " + validFrames
                + " Skipped non-data records: " + skippedNonData
                + " Malformed records: " + malformed
                + " Unsupported records: " + unsupported
                + " Channels: " + channels + channelIds
                + " First timestamp: " + formatTs(firstTimestampNanos)
                + " Last timestamp: " + formatTs(lastTimestampNanos)
                + " Timestamp regressions: " + timestampRegressions
                + " Date: " + (dateHeader.isBlank() ? "(absent)" : dateHeader)
                + " Timezone: " + (timezonePresent ? "present" : "absent")
                + " Base: " + base.name().toLowerCase(Locale.ROOT)
                + " Timestamps: " + timestampMode.name().toLowerCase(Locale.ROOT)
                + " Warning counts: " + warningCounts;
    }

    private static String formatTs(long nanos) {
        if (nanos == Long.MIN_VALUE) {
            return "(none)";
        }
        return nanos + " ns";
    }
}
