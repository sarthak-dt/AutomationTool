package com.stellar.platform.dbc.decode;

/**
 * Applies {@code physical = raw * factor + offset}.
 */
public final class PhysicalValueConverter {

    private PhysicalValueConverter() {
    }

    public static double toPhysical(long rawValue, double factor, double offset) {
        return rawValue * factor + offset;
    }

    public static boolean isOutsideBounds(double physical, double minimum, double maximum) {
        return physical < minimum || physical > maximum;
    }
}
