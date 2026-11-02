package com.stellar.platform.desktop.ui.signals;

/**
 * Shared geometry tokens for the Signals workstation layout.
 * Visual colors/fonts stay in CSS; sizing/alignment constants live here.
 */
public final class SignalsLayoutMetrics {

    public static final double PANEL_GAP = 8;
    public static final double INNER_PADDING = 8;
    public static final double CONTROL_GAP = 8;

    public static final double BROWSER_MIN_WIDTH = 320;
    public static final double BROWSER_PREF_WIDTH = 330;
    public static final double BROWSER_MAX_WIDTH = 350;

    public static final double INSPECTOR_MIN_WIDTH = 320;
    public static final double INSPECTOR_PREF_WIDTH = 330;
    public static final double INSPECTOR_MAX_WIDTH = 340;

    public static final double WORKSPACE_MIN_WIDTH = 640;

    public static final double CONTEXT_HEIGHT = 48;
    public static final double WORKSPACE_HEADER_HEIGHT = 36;
    public static final double CHIP_ROW_HEIGHT = 32;
    public static final double CHIP_HEIGHT = 28;
    public static final double CHIP_MIN_WIDTH = 160;
    public static final double CHIP_PREF_WIDTH = 200;
    public static final double CHIP_MAX_WIDTH = 240;
    public static final double CHIP_NAME_MAX_WIDTH = 100;

    public static final double STATUS_STRIP_HEIGHT = 22;

    public static final double TOOLBAR_HEIGHT = 36;
    public static final double CONTROL_HEIGHT = 28;
    public static final double PAGING_HEIGHT = 28;
    public static final double PAGINATION_BUTTON = 26;
    public static final double TABLE_ROW_HEIGHT = 39;
    public static final double TABLE_HEADER_HEIGHT = 56;
    public static final double TABLE_PREFERRED_MIN_HEIGHT = 280;
    public static final double FOOTER_HEIGHT = 30;

    public static final double INDEX_COL_MIN = 88;
    public static final double INDEX_COL_PREF = 112;
    public static final double INDEX_COL_MAX = 130;
    public static final double TIMESTAMP_COL_MIN = 125;
    public static final double TIMESTAMP_COL_PREF = 145;
    public static final double TIMESTAMP_COL_MAX = 165;
    public static final double DELTA_COL_MIN = 70;
    public static final double DELTA_COL_PREF = 80;
    public static final double DELTA_COL_MAX = 95;
    public static final double SIGNAL_COL_MIN = 135;
    public static final double SIGNAL_COL_PREF = 165;
    public static final double SIGNAL_COL_MAX = 220;

    public static final double DISPLAY_COMBO_WIDTH = 105;
    public static final double PRECISION_COMBO_WIDTH = 90;
    public static final double INDEX_FIELD_WIDTH = 108;
    public static final double TIME_FIELD_WIDTH = 122;
    public static final double NAVIGATE_MODE_WIDTH = 96;
    public static final double NAVIGATE_FIELD_WIDTH = 122;
    public static final double GO_BUTTON_WIDTH = 36;
    public static final double ROWS_COMBO_WIDTH = 82;

    /** Window/scene width at or above which the toolbar is one row, regardless of center-pane width. */
    public static final double TOOLBAR_DESKTOP_SCENE_WIDTH = 1366;

    public static final double FOCUS_PALETTE_WIDTH = 320;
    public static final double FOCUS_DETAILS_WIDTH = 320;
    public static final double FOCUS_EDGE_WIDTH = 28;

    public static final double NARROW_WORKSPACE_WRAP = 860;

    /** Usable Signals workbench width at or above which all three panes stay open. */
    public static final double WIDE_CONTENT_WIDTH = 1570;
    /** Usable width at or above which the browser stays open and empty details collapse. */
    public static final double STANDARD_CONTENT_WIDTH = 1250;

    public static final double BROWSER_STANDARD_MIN_WIDTH = 320;
    public static final double BROWSER_STANDARD_PREF_WIDTH = 330;
    public static final double BROWSER_STANDARD_MAX_WIDTH = 350;

    public static final double BROWSER_COMPACT_MIN_WIDTH = 300;
    public static final double BROWSER_COMPACT_PREF_WIDTH = 320;
    public static final double BROWSER_COMPACT_MAX_WIDTH = 340;

    public static final double DETAILS_RAIL_WIDTH = 28;
    public static final double FOOTER_WRAP_WIDTH = 980;

    private SignalsLayoutMetrics() {
    }
}
