package com.stellar.platform.processing.drive;

/**
 * Semantic roles used for drive-segment analysis. Names are matched from catalog metadata;
 * DBC identifiers are never hard-coded.
 */
public enum DriveSemanticRole {
    BRAKE_PEDAL,
    BRAKE_PRESSURE,
    BRAKE_SWITCH,
    BRAKE_STATUS,
    FUEL_RATE,
    FUEL_USED,
    FUEL_LEVEL,
    VEHICLE_SPEED,
    LONGITUDINAL_ACCEL
}
