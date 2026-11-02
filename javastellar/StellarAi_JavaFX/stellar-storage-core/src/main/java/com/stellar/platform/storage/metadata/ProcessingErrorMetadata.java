package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.processing.ProcessingFailureClass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable classified processing failure or warning.
 *
 * <p>Distinct from {@link DataQualityFindingMetadata}, which observes something about the recorded
 * measurement. This records something that went wrong while processing it, and is the source for
 * warning reasons, failure reasons and support diagnostics after reopen.</p>
 */
public record ProcessingErrorMetadata(
        String errorId,
        String projectId,
        String runId,
        String jobId,
        String sourceId,
        ProcessingFailureClass classification,
        String severity,
        boolean retryable,
        String summary,
        String technicalDetail,
        Instant createdAt
) {
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_ERROR = "ERROR";

    public ProcessingErrorMetadata {
        errorId = errorId == null || errorId.isBlank() ? UUID.randomUUID().toString() : errorId;
        projectId = require(projectId, "projectId");
        runId = runId == null ? "" : runId;
        jobId = jobId == null ? "" : jobId;
        sourceId = sourceId == null ? "" : sourceId;
        classification = Objects.requireNonNullElse(classification, ProcessingFailureClass.INTERNAL_ERROR);
        severity = severity == null || severity.isBlank() ? SEVERITY_ERROR : severity;
        summary = summary == null ? "" : summary;
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    /** Records the failure with the retry flag implied by its classification. */
    public static ProcessingErrorMetadata of(
            String projectId,
            String runId,
            String jobId,
            String sourceId,
            ProcessingFailureClass classification,
            String summary,
            String technicalDetail
    ) {
        ProcessingFailureClass resolved = classification == null
                ? ProcessingFailureClass.INTERNAL_ERROR : classification;
        return new ProcessingErrorMetadata(
                null, projectId, runId, jobId, sourceId, resolved,
                SEVERITY_ERROR, resolved.isRetryable(), summary, technicalDetail, null);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
