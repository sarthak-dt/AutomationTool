package com.stellar.platform.domain;

/**
 * A successfully decoded text label.
 * <p>
 * Existing artifacts store that outcome as {@link SignalValidity#VALID}, a non-finite
 * physical value, and the label. The physical number is not a measurement and is not
 * replaced with zero. {@link SignalValidity#usableWith} remains false, so numeric
 * calculations do not consume it. {@link SignalValidity#CONVERSION_FAILED} is a different
 * outcome and has no label.
 */
public final class CategoricalValue {

    private CategoricalValue() {
    }

    public static boolean isCategorical(SignalValidity validity, double physical, String text) {
        if (text == null || text.isBlank() || Double.isFinite(physical)) {
            return false;
        }
        // VALID is what new files store. UNRECORDED is what a layout-1 artifact
        // reads, because that layout has no validity byte. A failed conversion
        // has no label, so the text keeps the two outcomes apart.
        return validity == SignalValidity.VALID || validity == SignalValidity.UNRECORDED;
    }

    /** The trimmed label, or null when this sample is not a categorical value. */
    public static String label(SignalValidity validity, double physical, String text) {
        return isCategorical(validity, physical, text) ? text.trim() : null;
    }
}
