package com.stellar.platform.api.support;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured contextual logging for processing/API boundaries.
 * Never logs raw signal arrays, sample payloads, or credentials.
 */
public final class ProcessingLog {

    private static final Logger LOG = Logger.getLogger("com.stellar.platform.api");

    private ProcessingLog() {
    }

    public static void info(
            String event,
            String projectId,
            String runId,
            String jobId,
            String sourceId,
            String assetId,
            String status,
            Long durationMs,
            String diagnosticCode,
            String message
    ) {
        log(Level.INFO, event, projectId, runId, jobId, sourceId, assetId, status, durationMs, diagnosticCode, message);
    }

    public static void warn(
            String event,
            String projectId,
            String runId,
            String jobId,
            String sourceId,
            String assetId,
            String status,
            Long durationMs,
            String diagnosticCode,
            String message
    ) {
        log(Level.WARNING, event, projectId, runId, jobId, sourceId, assetId, status, durationMs, diagnosticCode, message);
    }

    public static void error(
            String event,
            String projectId,
            String runId,
            String message,
            Throwable error
    ) {
        String safe = sanitize(message);
        String line = "event=" + sanitize(event)
                + " projectId=" + sanitize(projectId)
                + " runId=" + sanitize(runId)
                + " message=" + safe;
        if (error == null) {
            LOG.log(Level.SEVERE, line);
        } else {
            LOG.log(Level.SEVERE, line, error);
        }
    }

    private static void log(
            Level level,
            String event,
            String projectId,
            String runId,
            String jobId,
            String sourceId,
            String assetId,
            String status,
            Long durationMs,
            String diagnosticCode,
            String message
    ) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("event=").append(sanitize(event));
        append(sb, "projectId", projectId);
        append(sb, "runId", runId);
        append(sb, "jobId", jobId);
        append(sb, "sourceId", sourceId);
        append(sb, "assetId", assetId);
        append(sb, "status", status);
        if (durationMs != null) {
            sb.append(" durationMs=").append(durationMs);
        }
        append(sb, "diagnosticCode", diagnosticCode);
        append(sb, "message", message);
        LOG.log(level, sb.toString());
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(' ').append(key).append('=').append(sanitize(value));
    }

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace('\n', ' ').replace('\r', ' ').trim();
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("secret") || lower.contains("token=")
                || lower.contains("authorization")) {
            return "<redacted>";
        }
        return v.length() > 500 ? v.substring(0, 500) + "…" : v;
    }
}
