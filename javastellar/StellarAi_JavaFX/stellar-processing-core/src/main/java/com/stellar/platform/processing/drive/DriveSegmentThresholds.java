package com.stellar.platform.processing.drive;

/**
 * Configurable engineering thresholds. Defaults are documented starting values, not product truth.
 *
 * @param stopSpeedKph GPS-derived speed at or below this counts as stopped
 * @param inferredBrakeDecelMps2 magnitude of deceleration used to infer braking when no brake signal exists
 * @param harshBrakeDecelMps2 magnitude of deceleration counted as harsh; {@code null} disables the metric
 */
public record DriveSegmentThresholds(
        double stopSpeedKph,
        double inferredBrakeDecelMps2,
        Double harshBrakeDecelMps2
) {
    public static final double DEFAULT_STOP_SPEED_KPH = 1.0;
    public static final double DEFAULT_INFERRED_BRAKE_DECEL_MPS2 = 2.5;
    public static final double DEFAULT_HARSH_BRAKE_DECEL_MPS2 = 4.0;

    public DriveSegmentThresholds {
        if (!(stopSpeedKph >= 0)) {
            stopSpeedKph = DEFAULT_STOP_SPEED_KPH;
        }
        if (!(inferredBrakeDecelMps2 > 0)) {
            inferredBrakeDecelMps2 = DEFAULT_INFERRED_BRAKE_DECEL_MPS2;
        }
        if (harshBrakeDecelMps2 != null && !(harshBrakeDecelMps2 > 0)) {
            harshBrakeDecelMps2 = DEFAULT_HARSH_BRAKE_DECEL_MPS2;
        }
    }

    public static DriveSegmentThresholds defaults() {
        return new DriveSegmentThresholds(
                DEFAULT_STOP_SPEED_KPH,
                DEFAULT_INFERRED_BRAKE_DECEL_MPS2,
                DEFAULT_HARSH_BRAKE_DECEL_MPS2);
    }
}
