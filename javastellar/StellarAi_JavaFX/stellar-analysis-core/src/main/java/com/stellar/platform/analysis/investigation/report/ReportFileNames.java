package com.stellar.platform.analysis.investigation.report;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Suggested HTML report filename. Does not touch the filesystem. */
public final class ReportFileNames {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault());
    private static final int MAX_PART = 80;

    private ReportFileNames() {
    }

    public static String suggest(String projectName, String investigationName, Instant generatedAt) {
        Instant when = generatedAt == null ? Instant.now() : generatedAt;
        return sanitize(projectName) + "_" + sanitize(investigationName) + "_" + STAMP.format(when) + "_Report.html";
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Report";
        }
        String cleaned = raw.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[._]+|[._]+$", "");
        if (cleaned.isBlank()) {
            return "Report";
        }
        if (cleaned.length() > MAX_PART) {
            cleaned = cleaned.substring(0, MAX_PART).replaceAll("[._]+$", "");
        }
        return cleaned.isBlank() ? "Report" : cleaned;
    }
}
