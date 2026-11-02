package com.stellar.platform.desktop.ui;

/**
 * Shared geometry tokens for major workstation pages (Decode, Processing, Engineering).
 * Colors and fonts stay in CSS; these constants keep padding, gaps and panel minima aligned.
 */
public final class WorkstationLayoutMetrics {

    public static final double PAGE_PADDING_H = 16;
    public static final double PAGE_PADDING_V = 12;
    public static final double SECTION_GAP = 10;
    public static final double CONTROL_GAP = 8;
    public static final double ACTION_BAR_HEIGHT = 52;
    public static final double ACTION_BAR_MAX_WRAPPED = 52;
    public static final double PANEL_HEADER_HEIGHT = 36;
    public static final double CONTROL_HEIGHT = 32;
    public static final double PRIMARY_BUTTON_MIN_WIDTH = 168;
    public static final double SECONDARY_BUTTON_MIN_WIDTH = 110;
    public static final double FIELD_MIN_WIDTH = 140;

    public static final double SIDE_PANEL_MIN = 260;
    public static final double SIDE_PANEL_PREF = 280;
    public static final double CENTER_PANEL_MIN = 380;

    public static final double BREAKPOINT_NARROW = 1280;
    public static final double BREAKPOINT_HD = 1366;
    public static final double BREAKPOINT_WIDE = 1536;
    public static final double BREAKPOINT_FULL = 1920;

    public static final double FINDINGS_TIME_MIN = 108;
    public static final double FINDINGS_SEVERITY_MIN = 88;
    public static final double FINDINGS_RULE_MIN = 120;
    public static final double FINDINGS_SUMMARY_MIN = 180;
    public static final double FINDINGS_DURATION_MIN = 78;
    public static final double FINDINGS_REVIEW_MIN = 88;

    public static final double BINDING_ROLE_MIN = 110;
    public static final double BINDING_EXPECTED_MIN = 80;
    public static final double BINDING_BOUND_MIN = 140;
    public static final double BINDING_STATUS_MIN = 120;

    public static final double ELAPSED_COLUMN_MIN = 80;
    public static final double ELAPSED_COLUMN_PREF = 92;

    public static final double DETAILS_DIALOG_WIDTH = 880;
    public static final double DETAILS_DIALOG_HEIGHT = 640;
    public static final double DETAILS_DIALOG_MIN_WIDTH = 720;
    public static final double DETAILS_DIALOG_MIN_HEIGHT = 480;

    private WorkstationLayoutMetrics() {
    }

    public static boolean collapseDetails(double width) {
        return false;
    }

    public static boolean hideSecondaryFindingColumns(double width) {
        return false;
    }
}
