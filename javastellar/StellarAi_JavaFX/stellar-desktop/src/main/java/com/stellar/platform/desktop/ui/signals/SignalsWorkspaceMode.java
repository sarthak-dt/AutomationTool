package com.stellar.platform.desktop.ui.signals;

/**
 * Signals workbench layout chosen from the available content width.
 * Wide keeps browser, workspace, and details. Standard and compact give the
 * table the width an empty details pane would otherwise hold.
 */
public enum SignalsWorkspaceMode {
    WIDE,
    STANDARD,
    COMPACT;

    public static SignalsWorkspaceMode forContentWidth(double width) {
        if (width >= SignalsLayoutMetrics.WIDE_CONTENT_WIDTH) {
            return WIDE;
        }
        if (width >= SignalsLayoutMetrics.STANDARD_CONTENT_WIDTH) {
            return STANDARD;
        }
        return COMPACT;
    }
}
