package com.stellar.platform.processing.map;

/**
 * How a configured role names its signal.
 *
 * <p>{@link #SIGNAL_REFERENCE} points at a catalog entry of a measurement that is loaded, which is
 * the normal case. {@link #MANUAL_TEXT} carries a name the engineer typed: the product may use
 * channel names that are absent from the measurement currently open, and the configuration still has
 * to be expressible. A manual mapping binds to whichever catalog signal carries that name.
 */
public enum RouteMappingMode {
    SIGNAL_REFERENCE("Selected signal"),
    MANUAL_TEXT("Manually entered");

    private final String label;

    RouteMappingMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RouteMappingMode parse(String raw) {
        if (raw == null) {
            return SIGNAL_REFERENCE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return SIGNAL_REFERENCE;
        }
    }
}
