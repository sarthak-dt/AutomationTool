package com.stellar.platform.processing.map;

/**
 * Tunables for route construction and GPS data-quality handling.
 *
 * @param pairToleranceNanos    maximum |Δt| when pairing a longitude/altitude sample to a latitude
 *                              sample; {@code null} derives it from the observed sample interval
 * @param maxJumpMeters         distance above which a step is treated as a GPS outlier/gap
 * @param maxTimeGapNanos       time gap above which the route is treated as interrupted
 * @param elevationNoiseMeters  minimum climb counted towards elevation gain, so GPS altitude noise
 *                              cannot accumulate into an unrealistic figure
 */
public record MapRouteBuildOptions(
        Long pairToleranceNanos,
        double maxJumpMeters,
        long maxTimeGapNanos,
        double elevationNoiseMeters
) {

    private static final MapRouteBuildOptions DEFAULTS =
            new MapRouteBuildOptions(null, 1_000.0, 5_000_000_000L, 1.0);

    public MapRouteBuildOptions {
        if (maxJumpMeters <= 0) {
            throw new IllegalArgumentException("maxJumpMeters must be > 0");
        }
        if (maxTimeGapNanos <= 0) {
            throw new IllegalArgumentException("maxTimeGapNanos must be > 0");
        }
        if (elevationNoiseMeters < 0) {
            throw new IllegalArgumentException("elevationNoiseMeters must be >= 0");
        }
    }

    public static MapRouteBuildOptions defaults() {
        return DEFAULTS;
    }
}
