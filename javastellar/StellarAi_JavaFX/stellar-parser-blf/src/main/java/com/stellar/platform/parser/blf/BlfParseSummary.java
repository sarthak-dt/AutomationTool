package com.stellar.platform.parser.blf;

import java.util.Map;
import java.util.Set;

final class BlfParseSummary {

    private final String fileName;
    private final String binLogVersion;
    private final String recordingStart;
    private final long objects;
    private final long containers;
    private final long classicCan;
    private final long canFd;
    private final long frames;
    private final long unsupported;
    private final long nonData;
    private final long malformed;
    private final int channels;
    private final Set<Integer> channelIds;
    private final int uniqueCanIds;
    private final long firstTimestampNanos;
    private final long lastTimestampNanos;
    private final long compressedBytes;
    private final long decompressedBytes;
    private final Map<Integer, Integer> skippedTypes;

    BlfParseSummary(
            String fileName,
            BlfFileHeader header,
            BlfDiagnostics diagnostics,
            Set<Integer> channelIds,
            int uniqueCanIds,
            long firstTimestampNanos,
            long lastTimestampNanos
    ) {
        this.fileName = fileName == null ? "" : fileName;
        this.binLogVersion = header == null ? "?" : header.binLogVersion();
        this.recordingStart = header == null ? "" : header.recordingStartUtcText();
        this.objects = diagnostics.objects();
        this.containers = diagnostics.containers();
        this.classicCan = diagnostics.canObjects();
        this.canFd = diagnostics.canFdObjects();
        this.frames = diagnostics.framesEmitted();
        this.unsupported = diagnostics.unsupported();
        this.nonData = diagnostics.nonData();
        this.malformed = diagnostics.malformed();
        this.channels = channelIds.size();
        this.channelIds = Set.copyOf(channelIds);
        this.uniqueCanIds = uniqueCanIds;
        this.firstTimestampNanos = firstTimestampNanos;
        this.lastTimestampNanos = lastTimestampNanos;
        this.compressedBytes = diagnostics.compressedBytes();
        this.decompressedBytes = diagnostics.decompressedBytes();
        this.skippedTypes = diagnostics.skippedTypes();
    }

    long frames() {
        return frames;
    }

    String binLogVersion() {
        return binLogVersion;
    }

    String recordingStart() {
        return recordingStart;
    }

    long objects() {
        return objects;
    }

    long containers() {
        return containers;
    }

    long classicCan() {
        return classicCan;
    }

    long canFd() {
        return canFd;
    }

    long unsupported() {
        return unsupported;
    }

    long nonData() {
        return nonData;
    }

    long malformed() {
        return malformed;
    }

    int channels() {
        return channels;
    }

    int uniqueCanIds() {
        return uniqueCanIds;
    }

    long firstTimestampNanos() {
        return firstTimestampNanos;
    }

    long lastTimestampNanos() {
        return lastTimestampNanos;
    }

    String formatLogLine() {
        return "BLF Parsing Summary"
                + " File: " + fileName
                + " Header version: " + binLogVersion
                + " Recording start: " + (recordingStart.isBlank() ? "(absent)" : recordingStart)
                + " Containers: " + containers
                + " Objects: " + objects
                + " Classic CAN: " + classicCan
                + " CAN FD: " + canFd
                + " Frames: " + frames
                + " Channels: " + channels + channelIds
                + " Unique CAN identities: " + uniqueCanIds
                + " Unsupported objects: " + unsupported
                + " Non-data objects: " + nonData
                + " Malformed: " + malformed
                + " Compressed bytes: " + compressedBytes
                + " Decompressed bytes: " + decompressedBytes
                + " First timestamp: " + formatTs(firstTimestampNanos)
                + " Last timestamp: " + formatTs(lastTimestampNanos)
                + " Skipped types: " + skippedTypes;
    }

    private static String formatTs(long nanos) {
        if (nanos == Long.MIN_VALUE) {
            return "(none)";
        }
        return nanos + " ns";
    }
}
