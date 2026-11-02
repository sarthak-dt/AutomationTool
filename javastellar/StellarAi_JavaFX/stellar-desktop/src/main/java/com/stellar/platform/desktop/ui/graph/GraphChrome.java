package com.stellar.platform.desktop.ui.graph;

import javafx.scene.paint.Color;

/**
 * Waveform chrome (axes, grid, tooltip, navigator). Not the signal-series palette
 * and not the application UI token set.
 */
public final class GraphChrome {

    public static final Color AXIS_LABEL = Color.web("#6B7F90");
    public static final Color AXIS_SECONDARY = Color.web("#53677A");
    public static final Color TITLE = Color.web("#17324D");
    public static final Color TOOLTIP_TITLE = Color.web("#263B50");
    public static final Color TOOLTIP_VALUE = Color.web("#34495E");
    public static final Color HIDDEN_SERIES = Color.web("#9AA8B5");
    public static final Color GRID = Color.web("#DBE5EC");
    public static final Color GRID_SOFT = Color.web("#E6EDF5");
    public static final Color GRID_TIME = Color.web("#EDF2F6");
    public static final Color CROSSHAIR = Color.web("#7B8FA3");
    public static final Color CURSOR_A = Color.web("#F59E0B");
    public static final Color CURSOR_B = Color.web("#A78BFA");
    public static final Color ANALYSIS_EVENT = Color.web("#0E7490");
    public static final Color ANALYSIS_BAND = Color.rgb(14, 116, 144, 0.18);
    public static final Color NAVIGATOR_FILL = Color.web("#F3F7FB");
    public static final Color NAVIGATOR_STROKE = Color.web("#C9D8EA");
    public static final Color NAVIGATOR_TRACE = Color.web("#7AA7F8");
    public static final Color NAVIGATOR_WINDOW = Color.web("#4F86F7");
    public static final Color TOOLTIP_BORDER = Color.web("#D7E1EA");
    public static final Color TOOLTIP_SURFACE = Color.rgb(255, 255, 255, 0.97);

    private GraphChrome() {
    }
}
