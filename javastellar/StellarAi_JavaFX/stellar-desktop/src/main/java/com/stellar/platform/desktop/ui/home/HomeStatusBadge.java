package com.stellar.platform.desktop.ui.home;

import com.stellar.platform.desktop.presentation.ProjectJourneyState;
import com.stellar.platform.desktop.presentation.ProjectStatusLabel;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;

/**
 * Semantic status chip. Styling lives in CSS ({@code status-*} classes).
 */
public final class HomeStatusBadge {

    private HomeStatusBadge() {
    }

    public static Label create(ProjectJourneyState state, boolean countsAvailable) {
        String text = countsAvailable && state != null
                ? state.statusLabel().display()
                : "Needs attention";
        Label badge = new Label(text);
        badge.getStyleClass().addAll("status-badge", cssClass(state, countsAvailable));
        badge.setTextOverrun(OverrunStyle.ELLIPSIS);
        badge.setMinWidth(0);
        badge.setMaxWidth(Double.MAX_VALUE);
        badge.setTooltip(new Tooltip(text));
        return badge;
    }

    public static String cssClass(ProjectJourneyState state, boolean countsAvailable) {
        if (!countsAvailable || state == null) {
            return "status-error";
        }
        ProjectStatusLabel label = state.statusLabel();
        return switch (label) {
            case ANALYSIS_READY -> "status-ready";
            case ANALYSIS_READY_WITH_WARNINGS -> "status-warning";
            case PROCESSING, READY_TO_PROCESS -> "status-processing";
            case FAILED -> "status-error";
            case SETUP, ACTION_REQUIRED -> "status-neutral";
        };
    }
}
