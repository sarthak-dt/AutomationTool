package com.stellar.platform.storage.metadata;

import java.util.Locale;

/**
 * Event kinds recorded in the append-only audit log.
 *
 * <p>Names are persisted verbatim, so existing constants must not be renamed. Unknown values read
 * from a newer build are tolerated via {@link #parse(String)} rather than failing the project open.</p>
 */
public enum AuditEventType {
    PROJECT_CREATED,
    PROJECT_OPENED,
    PROJECT_SAVED,
    SOURCE_ADDED,
    SOURCE_REMOVED,
    DEFINITION_ADDED,
    PROCESSING_STARTED,
    PROCESSING_CHECKPOINTED,
    PROCESSING_RESUMED,
    CHECKPOINT_INVALIDATED,
    PROCESSING_INTERRUPTED,
    PROCESSING_RETRIED,
    PROCESSING_COMPLETED,
    PROCESSING_WARNING,
    PROCESSING_FAILED,
    WORKER_STARTED,
    WORKER_RESTARTED,
    WORKER_CRASHED,
    ARTIFACT_REUSED,
    ARTIFACT_INVALIDATED,
    GRAPH_ANNOTATION_ADDED,
    EXPORT_STARTED,
    EXPORT_COMPLETED,
    EXPORT_FAILED,
    RULE_CREATED,
    RULE_UPDATED,
    RULE_PACK_IMPORTED,
    DIAGNOSTICS_STARTED,
    DIAGNOSTICS_COMPLETED,
    DIAGNOSTICS_COMPLETED_WITH_FINDINGS,
    DIAGNOSTICS_FAILED,
    INVESTIGATION_CREATED,
    INVESTIGATION_UPDATED,
    INVESTIGATION_COMPLETED,
    REPORT_GENERATION_STARTED,
    REPORT_GENERATED,
    REPORT_FAILED,
    INVESTIGATION_EXPORTED,
    /** Persisted value not understood by this build. Never written, only read. */
    UNKNOWN;

    public static AuditEventType parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return UNKNOWN;
        }
    }
}
