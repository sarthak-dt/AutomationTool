package com.stellar.platform.desktop.presentation;

/**
 * Presentation copy for signal counts. Workspace KPIs and project rows use unique
 * indexed signal keys — the same concept as the Signals page catalog.
 */
public final class SignalMetricPresenter {

    public static final String WORKSPACE_KPI_TITLE = "Signals";
    public static final String WORKSPACE_KPI_TOOLTIP =
            "Unique indexed signals across all saved projects.";
    public static final String PROJECT_ROW_TOOLTIP =
            "Unique indexed signals in this project.";

    private SignalMetricPresenter() {
    }
}
