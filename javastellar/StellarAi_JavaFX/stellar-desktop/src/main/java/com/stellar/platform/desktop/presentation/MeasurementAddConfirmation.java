package com.stellar.platform.desktop.presentation;

/**
 * Presentation copy for multi-file measurement add. Does not change parser or import rules.
 */
public final class MeasurementAddConfirmation {

    private MeasurementAddConfirmation() {
    }

    public static boolean requiresConfirmation(int selectedCount) {
        return selectedCount > 1;
    }

    public static String heading(int selectedCount) {
        return selectedCount + " measurement files selected";
    }

    public static String question(int selectedCount) {
        return "Add " + selectedCount + " measurement files?";
    }
}
