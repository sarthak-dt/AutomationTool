package com.stellar.platform.desktop.presentation;

import java.util.List;

/**
 * Project-level Sources header chip. Uses the same {@link SourceStatusPresenter.View}
 * labels as row badges and the footer — inventory READY is not a substitute for processed.
 */
public final class SourceWorkspaceHeaderSummary {

    private SourceWorkspaceHeaderSummary() {
    }

    public static String rollup(List<SourceStatusPresenter.View> views) {
        if (views == null || views.isEmpty()) {
            return "";
        }
        boolean processing = false;
        boolean reading = false;
        boolean processed = false;
        boolean ready = false;
        String fallback = "";
        for (SourceStatusPresenter.View view : views) {
            if (view == null) {
                continue;
            }
            String footer = view.footerLabel();
            if (footer == null || footer.isBlank()) {
                continue;
            }
            if (fallback.isBlank()) {
                fallback = footer;
            }
            switch (footer) {
                case "Processing", "PROCESSING" -> processing = true;
                case "Reading" -> reading = true;
                case "Processed", "COMPLETED" -> processed = true;
                case "Ready" -> ready = true;
                default -> {
                }
            }
        }
        if (processing) {
            return ProcessingStatusLabels.PROCESSING;
        }
        if (processed) {
            return ProcessingStatusLabels.COMPLETED;
        }
        if (reading) {
            return "Reading";
        }
        if (ready) {
            return "Ready";
        }
        return fallback;
    }
}
