package com.stellar.platform.domain;

/**
 * A DBC value-table label stored beside a finite physical sample.
 * <p>
 * The label was chosen from the raw code when the frame was decoded. Display
 * must not recover it by rounding the physical value. This is separate from
 * {@link CategoricalValue}, which is a non-finite text sample and is what
 * report columns already use.
 */
public final class EnumeratedState {

    private EnumeratedState() {
    }

    /**
     * Value-table text for a usable finite sample, or null.
     * Invalid, inactive, failed, and non-finite samples are not states.
     * A null validity still accepts a label, except the decoder's own
     * {@code invalid} and {@code inactive} words, which are not DBC text.
     */
    public static String text(SignalValidity validity, double physical, String text) {
        if (text == null || text.isBlank() || !Double.isFinite(physical)) {
            return null;
        }
        if (validity == SignalValidity.INVALID_RAW_CODE
                || validity == SignalValidity.INACTIVE_RAW_CODE
                || validity == SignalValidity.CONVERSION_FAILED
                || validity == SignalValidity.NON_FINITE_VALUE
                || validity == SignalValidity.UNRECORDED) {
            return null;
        }
        if (validity != null && !validity.usableWith(physical)) {
            return null;
        }
        String trimmed = text.trim();
        if (validity == null && ("invalid".equals(trimmed) || "inactive".equals(trimmed))) {
            return null;
        }
        return trimmed;
    }

    /** {@code raw = label}, using the stored raw code. Null when this sample is not a state. */
    public static String codeAndText(Long raw, SignalValidity validity, double physical, String text) {
        String label = text(validity, physical, text);
        if (label == null || raw == null) {
            return null;
        }
        return raw + " = " + label;
    }
}
