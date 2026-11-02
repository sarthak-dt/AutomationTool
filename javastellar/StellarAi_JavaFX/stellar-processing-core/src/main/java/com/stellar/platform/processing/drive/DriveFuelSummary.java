package com.stellar.platform.processing.drive;

/**
 * Fuel used / economy for one A→B interval. Absent or untrusted data is {@link Availability#NOT_AVAILABLE},
 * never a fabricated zero.
 */
public record DriveFuelSummary(
        Availability availability,
        Double fuelUsedLiters,
        Double fuelEconomyKmPerLiter,
        String sourceSignal,
        String detail
) {
    public enum Availability {
        AVAILABLE,
        NOT_AVAILABLE,
        UNKNOWN_UNITS,
        TANK_LEVEL_UNRELIABLE
    }

    public static DriveFuelSummary available(double liters, Double economyKmPerLiter, String source) {
        return new DriveFuelSummary(Availability.AVAILABLE, liters, economyKmPerLiter, source, "");
    }

    public static DriveFuelSummary unavailable(String detail) {
        return new DriveFuelSummary(Availability.NOT_AVAILABLE, null, null, "", detail);
    }

    public static DriveFuelSummary unknownUnits(String signal, String unit) {
        return new DriveFuelSummary(Availability.UNKNOWN_UNITS, null, null, signal,
                "Fuel units '" + unit + "' are not convertible.");
    }

    public static DriveFuelSummary tankLevelUnreliable(String signal) {
        return new DriveFuelSummary(Availability.TANK_LEVEL_UNRELIABLE, null, null, signal,
                "Tank fuel level is not authoritative fuel used.");
    }

    public boolean displayable() {
        return availability == Availability.AVAILABLE && fuelUsedLiters != null;
    }

    public String displayLabel() {
        return switch (availability) {
            case AVAILABLE -> null;
            case UNKNOWN_UNITS, NOT_AVAILABLE, TANK_LEVEL_UNRELIABLE -> "Not available";
        };
    }
}
