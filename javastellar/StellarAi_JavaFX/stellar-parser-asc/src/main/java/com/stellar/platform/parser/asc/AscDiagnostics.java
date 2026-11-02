package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded diagnostic emission. Counts every event; retains only a small number of
 * representative warnings so a corrupt file cannot exhaust heap.
 */
final class AscDiagnostics {

    static final int MAX_WARNINGS_PER_CODE = 32;
    static final int MAX_CONTEXT_CHARS = 96;

    private final WarningConsumer sink;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int emitted;

    AscDiagnostics(WarningConsumer sink) {
        this.sink = sink == null ? w -> { } : sink;
    }

    void warning(String code, String reason, long lineNumber, String context) {
        int next = counts.merge(code, 1, Integer::sum);
        if (next > MAX_WARNINGS_PER_CODE) {
            return;
        }
        String safe = truncate(context);
        String message = reason + (safe.isEmpty() ? "" : ": " + safe);
        sink.onWarning(new ProcessingWarning(code, message, SourceReference.ofLine(lineNumber)));
        emitted++;
    }

    int count(String code) {
        return counts.getOrDefault(code, 0);
    }

    int malformed() {
        return count(AscFileReader.WARN_MALFORMED);
    }

    int unsupportedCanFd() {
        return count(AscFileReader.WARN_UNSUPPORTED_CAN_FD);
    }

    int unsupportedFrame() {
        return count(AscFileReader.WARN_UNSUPPORTED_FRAME);
    }

    int unsupportedRecord() {
        return count(AscFileReader.WARN_UNSUPPORTED_RECORD);
    }

    int skippedNonData() {
        return unsupportedCanFd() + unsupportedFrame() + unsupportedRecord();
    }

    int emittedWarnings() {
        return emitted;
    }

    Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.length() <= MAX_CONTEXT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_CONTEXT_CHARS) + "…";
    }

    static String safeContext(String line) {
        return truncate(Objects.requireNonNullElse(line, ""));
    }
}
