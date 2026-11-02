package com.stellar.platform.parser.blf;

import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.util.LinkedHashMap;
import java.util.Map;

final class BlfDiagnostics {

    static final String WARN_MALFORMED = "BLF_MALFORMED_OBJECT";
    static final String WARN_CONTAINER = "BLF_CONTAINER";
    static final int MAX_WARNINGS = 32;

    private final WarningConsumer sink;
    private long objects;
    private long canObjects;
    private long canFdObjects;
    private long framesEmitted;
    private long unsupported;
    private long nonData;
    private long malformed;
    private long containers;
    private long compressedBytes;
    private long decompressedBytes;
    private int warningsEmitted;
    private final Map<Integer, Integer> skippedTypes = new LinkedHashMap<>();

    BlfDiagnostics(WarningConsumer sink) {
        this.sink = sink == null ? w -> { } : sink;
    }

    void noteObject(int type) {
        objects++;
    }

    void noteCan() {
        canObjects++;
    }

    void noteCanFd() {
        canFdObjects++;
    }

    void noteFrame() {
        framesEmitted++;
    }

    void noteContainer(int compressed, int decompressed) {
        containers++;
        compressedBytes += Math.max(0, compressed);
        decompressedBytes += Math.max(0, decompressed);
    }

    void noteNonData(int type) {
        nonData++;
        skippedTypes.merge(type, 1, Integer::sum);
    }

    void noteUnknown(int type) {
        unsupported++;
        skippedTypes.merge(type, 1, Integer::sum);
    }

    void malformed(String message, long offset) {
        malformed++;
        if (warningsEmitted >= MAX_WARNINGS) {
            return;
        }
        warningsEmitted++;
        sink.onWarning(new ProcessingWarning(
                WARN_MALFORMED, message, new SourceReference(-1L, offset)));
    }

    void containerWarning(String message, long offset) {
        if (warningsEmitted >= MAX_WARNINGS) {
            return;
        }
        warningsEmitted++;
        sink.onWarning(new ProcessingWarning(
                WARN_CONTAINER, message, new SourceReference(-1L, offset)));
    }

    long objects() {
        return objects;
    }

    long canObjects() {
        return canObjects;
    }

    long canFdObjects() {
        return canFdObjects;
    }

    long framesEmitted() {
        return framesEmitted;
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

    long containers() {
        return containers;
    }

    long compressedBytes() {
        return compressedBytes;
    }

    long decompressedBytes() {
        return decompressedBytes;
    }

    Map<Integer, Integer> skippedTypes() {
        return Map.copyOf(skippedTypes);
    }
}
