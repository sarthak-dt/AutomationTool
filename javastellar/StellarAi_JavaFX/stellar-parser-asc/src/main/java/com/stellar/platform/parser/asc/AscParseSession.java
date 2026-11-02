package com.stellar.platform.parser.asc;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Per-file parse session. Header state never leaks across files.
 */
final class AscParseSession {

    private AscNumericBase base;
    private boolean baseDeclared;
    private AscTimestampMode timestampMode = AscTimestampMode.UNSPECIFIED;
    private String dateHeader = "";
    private boolean timezonePresent;
    private boolean internalEventsLogged;
    private boolean emittedFrame;
    private long validFrames;
    private long skippedNonData;
    private long firstTimestampNanos = Long.MIN_VALUE;
    private long lastTimestampNanos = Long.MIN_VALUE;
    private long previousTimestampNanos = Long.MIN_VALUE;
    private long timestampRegressions;
    private final Set<Integer> channels = new LinkedHashSet<>();

    AscNumericBase baseOrDefault() {
        return base == null ? AscNumericBase.HEX : base;
    }

    boolean baseDeclared() {
        return baseDeclared;
    }

    void applyBase(AscNumericBase next) {
        this.base = next;
        this.baseDeclared = true;
    }

    AscTimestampMode timestampMode() {
        return timestampMode;
    }

    void timestampMode(AscTimestampMode mode) {
        this.timestampMode = mode;
    }

    String dateHeader() {
        return dateHeader;
    }

    void dateHeader(String value) {
        this.dateHeader = value == null ? "" : value;
    }

    boolean timezonePresent() {
        return timezonePresent;
    }

    void timezonePresent(boolean present) {
        this.timezonePresent = present;
    }

    boolean internalEventsLogged() {
        return internalEventsLogged;
    }

    void internalEventsLogged(boolean value) {
        this.internalEventsLogged = value;
    }

    boolean emittedFrame() {
        return emittedFrame;
    }

    void noteFrame(int channel, long timestampNanos) {
        emittedFrame = true;
        validFrames++;
        channels.add(channel);
        if (firstTimestampNanos == Long.MIN_VALUE) {
            firstTimestampNanos = timestampNanos;
        }
        lastTimestampNanos = timestampNanos;
        if (previousTimestampNanos != Long.MIN_VALUE && timestampNanos < previousTimestampNanos) {
            timestampRegressions++;
        }
        previousTimestampNanos = timestampNanos;
    }

    void noteSkippedNonData() {
        skippedNonData++;
    }

    long validFrames() {
        return validFrames;
    }

    long skippedNonData() {
        return skippedNonData;
    }

    long firstTimestampNanos() {
        return firstTimestampNanos;
    }

    long lastTimestampNanos() {
        return lastTimestampNanos;
    }

    long timestampRegressions() {
        return timestampRegressions;
    }

    int channelCount() {
        return channels.size();
    }

    Set<Integer> channels() {
        return Set.copyOf(channels);
    }

    String timeBaseLabel() {
        return timestampMode == AscTimestampMode.UNSPECIFIED
                ? "seconds-as-nanos"
                : timestampMode.name().toLowerCase(Locale.ROOT) + "-seconds-as-nanos";
    }
}
