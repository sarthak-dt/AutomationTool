package com.stellar.platform.processing.drive.speed;

/**
 * Quality of the selected source over the requested time range. Catalog extrema are not used.
 */
public record SpeedQualityAssessment(
        long samplesVisited,
        int rejectedIsolatedPeaks,
        int duplicateTimestamps,
        int nonFiniteValues,
        int windowSize,
        double coverage
) {
    public static SpeedQualityAssessment empty() {
        return new SpeedQualityAssessment(0L, 0, 0, 0, VehicleSpeedPeakScanner.WINDOW, 0);
    }

    public SpeedQualityAssessment {
        samplesVisited = Math.max(0L, samplesVisited);
        rejectedIsolatedPeaks = Math.max(0, rejectedIsolatedPeaks);
        duplicateTimestamps = Math.max(0, duplicateTimestamps);
        nonFiniteValues = Math.max(0, nonFiniteValues);
        windowSize = windowSize <= 0 ? VehicleSpeedPeakScanner.WINDOW : windowSize;
        if (!(coverage >= 0)) {
            coverage = 0;
        }
    }
}
