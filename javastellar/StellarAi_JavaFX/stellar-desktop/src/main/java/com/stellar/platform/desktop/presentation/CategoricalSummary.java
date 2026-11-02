package com.stellar.platform.desktop.presentation;

/**
 * Copy used when a signal has samples but no usable numeric range.
 */
public final class CategoricalSummary {

    public static final String UNAVAILABLE = "n/a";

    private CategoricalSummary() {
    }

    public static String engineeringCue(String label) {
        return "Categorical — " + label;
    }
}
