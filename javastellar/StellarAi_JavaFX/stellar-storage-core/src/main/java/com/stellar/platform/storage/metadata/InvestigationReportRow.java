package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.UUID;

public record InvestigationReportRow(
        String reportId,
        String investigationId,
        String projectId,
        String name,
        String format,
        String status,
        String destination,
        String message,
        Instant createdAt,
        Instant finishedAt
) {
    public InvestigationReportRow {
        reportId = reportId == null || reportId.isBlank() ? UUID.randomUUID().toString() : reportId;
        investigationId = investigationId == null ? "" : investigationId;
        projectId = projectId == null ? "" : projectId;
        name = name == null ? "" : name;
        format = format == null || format.isBlank() ? "HTML" : format;
        status = status == null ? "QUEUED" : status;
        destination = destination == null ? "" : destination;
        message = message == null ? "" : message;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
