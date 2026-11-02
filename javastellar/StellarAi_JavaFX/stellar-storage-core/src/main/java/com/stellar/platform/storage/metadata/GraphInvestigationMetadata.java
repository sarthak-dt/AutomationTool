package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted graph investigation for one project. Traces are stored as a reconstructable blob
 * of stable signal identities — never Canvas, LOD arrays or sample values.
 */
public record GraphInvestigationMetadata(
        String projectId,
        String layoutMode,
        Long viewportStartNanos,
        Long viewportEndNanos,
        Long defaultStartNanos,
        Long defaultEndNanos,
        Long cursorANanos,
        Long cursorBNanos,
        String notes,
        String tracesBlob,
        Instant updatedAt,
        String focusKeyBlob,
        int analysisTab,
        boolean inspectorVisible,
        boolean plotSettingsVisible,
        String signalsDisplayMode,
        String signalsPrecision,
        int presentationVersion
) {
    public static final int PRESENTATION_VERSION = 1;

    /** Compatibility constructor for pre-v11 call sites. */
    public GraphInvestigationMetadata(
            String projectId,
            String layoutMode,
            Long viewportStartNanos,
            Long viewportEndNanos,
            Long defaultStartNanos,
            Long defaultEndNanos,
            Long cursorANanos,
            Long cursorBNanos,
            String notes,
            String tracesBlob,
            Instant updatedAt
    ) {
        this(projectId, layoutMode, viewportStartNanos, viewportEndNanos, defaultStartNanos, defaultEndNanos,
                cursorANanos, cursorBNanos, notes, tracesBlob, updatedAt,
                "", 0, true, false, "", "", PRESENTATION_VERSION);
    }

    public GraphInvestigationMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        layoutMode = layoutMode == null || layoutMode.isBlank() ? "STACKED" : layoutMode;
        notes = notes == null ? "" : notes;
        tracesBlob = tracesBlob == null ? "" : tracesBlob;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        focusKeyBlob = focusKeyBlob == null ? "" : focusKeyBlob;
        if (analysisTab < 0) {
            analysisTab = 0;
        }
        signalsDisplayMode = signalsDisplayMode == null ? "" : signalsDisplayMode;
        signalsPrecision = signalsPrecision == null ? "" : signalsPrecision;
        if (presentationVersion < 1) {
            presentationVersion = PRESENTATION_VERSION;
        }
    }
}
