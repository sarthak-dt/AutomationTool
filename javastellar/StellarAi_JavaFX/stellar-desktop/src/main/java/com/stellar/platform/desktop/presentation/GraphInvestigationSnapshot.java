package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;

/**
 * Persistable graph investigation. Contains only reconstructable logical state —
 * never Canvas, LOD arrays, futures or JavaFX nodes.
 */
public record GraphInvestigationSnapshot(
        String projectId,
        String layoutMode,
        Long viewportStartNanos,
        Long viewportEndNanos,
        Long defaultStartNanos,
        Long defaultEndNanos,
        Long cursorANanos,
        Long cursorBNanos,
        String notes,
        List<Trace> traces,
        SignalCatalogKey focusKey,
        int analysisTab,
        boolean inspectorVisible,
        boolean plotSettingsVisible,
        String signalsDisplayMode,
        String signalsPrecision
) {
    public GraphInvestigationSnapshot(
            String projectId,
            String layoutMode,
            Long viewportStartNanos,
            Long viewportEndNanos,
            Long defaultStartNanos,
            Long defaultEndNanos,
            Long cursorANanos,
            Long cursorBNanos,
            String notes,
            List<Trace> traces
    ) {
        this(projectId, layoutMode, viewportStartNanos, viewportEndNanos, defaultStartNanos, defaultEndNanos,
                cursorANanos, cursorBNanos, notes, traces, null, 0, true, false, "", "");
    }

    public GraphInvestigationSnapshot {
        projectId = projectId == null ? "" : projectId;
        layoutMode = GraphInvestigationState.OVERLAY.equals(layoutMode)
                ? GraphInvestigationState.OVERLAY
                : GraphInvestigationState.STACKED;
        notes = notes == null ? "" : notes;
        traces = List.copyOf(traces == null ? List.of() : traces);
        if (analysisTab < 0) {
            analysisTab = 0;
        }
        signalsDisplayMode = signalsDisplayMode == null ? "" : signalsDisplayMode;
        signalsPrecision = signalsPrecision == null ? "" : signalsPrecision;
    }

    /**
     * Project checkpoints keep the selected signals and draw them. A legend hide is not selection.
     */
    public GraphInvestigationSnapshot withoutTemporaryVisibility() {
        List<Trace> visible = traces.stream()
                .map(trace -> trace.hidden() ? new Trace(trace.key(), false, trace.colorIndex()) : trace)
                .toList();
        return new GraphInvestigationSnapshot(
                projectId,
                layoutMode,
                viewportStartNanos,
                viewportEndNanos,
                defaultStartNanos,
                defaultEndNanos,
                cursorANanos,
                cursorBNanos,
                notes,
                visible,
                focusKey,
                analysisTab,
                inspectorVisible,
                plotSettingsVisible,
                signalsDisplayMode,
                signalsPrecision);
    }

    public record Trace(SignalCatalogKey key, boolean hidden, int colorIndex) {
        public Trace {
            if (colorIndex < 0) {
                colorIndex = 0;
            }
        }
    }
}
