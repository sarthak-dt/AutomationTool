package com.stellar.platform.processing.drive.speed;

/**
 * Semantic authority of a speed source. Lower tier wins. Not a list of signal names.
 */
public enum SpeedSourceKind {
    AUTHORITATIVE_VEHICLE(1, "Vehicle speed"),
    ECU_DYNAMICS(2, "Vehicle dynamics speed"),
    GPS_RECEIVER_SPEED(3, "GPS receiver"),
    GPS_COORDINATE_DERIVED(4, "GPS-derived");

    private final int tier;
    private final String displayLabel;

    SpeedSourceKind(int tier, String displayLabel) {
        this.tier = tier;
        this.displayLabel = displayLabel;
    }

    /** 1 is most authoritative. */
    public int tier() {
        return tier;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public boolean physicalVehicle() {
        return this == AUTHORITATIVE_VEHICLE || this == ECU_DYNAMICS;
    }
}
