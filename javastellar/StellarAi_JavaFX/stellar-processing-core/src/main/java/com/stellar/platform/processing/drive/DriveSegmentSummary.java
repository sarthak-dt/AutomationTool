package com.stellar.platform.processing.drive;

import com.stellar.platform.processing.drive.speed.SpeedSourceResolution;

/**
 * Compact A→B engineering summary. Optional metrics stay {@code null} / unavailable rather than zero.
 */
public record DriveSegmentSummary(
        boolean fullDrive,
        long startTimestampNanos,
        long endTimestampNanos,
        DriveMotionMetrics motion,
        DriveBrakingSummary braking,
        DriveFuelSummary fuel,
        DriveSignalHealthSummary health,
        int eventCount,
        SpeedSourceResolution speed
) {
    public DriveSegmentSummary {
        if (motion == null) {
            motion = DriveMotionMetrics.compute(null, 0, 0, null, null);
        }
        if (braking == null) {
            braking = DriveBrakingSummary.none();
        }
        if (fuel == null) {
            fuel = DriveFuelSummary.unavailable("No fuel analysis.");
        }
        if (health == null) {
            health = DriveSignalHealthSummary.none();
        }
        eventCount = Math.max(0, eventCount);
    }

    public DriveSegmentSummary(
            boolean fullDrive,
            long startTimestampNanos,
            long endTimestampNanos,
            DriveMotionMetrics motion,
            DriveBrakingSummary braking,
            DriveFuelSummary fuel,
            DriveSignalHealthSummary health,
            int eventCount
    ) {
        this(fullDrive, startTimestampNanos, endTimestampNanos, motion, braking, fuel, health, eventCount, null);
    }

    public static DriveSegmentSummary empty() {
        return new DriveSegmentSummary(true, 0, 0, null, null, null, null, 0, null);
    }
}
