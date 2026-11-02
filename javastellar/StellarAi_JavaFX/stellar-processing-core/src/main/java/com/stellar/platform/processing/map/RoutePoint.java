package com.stellar.platform.processing.map;

/**
 * One accepted GPS observation: a latitude sample paired with the longitude sample that is
 * nearest in time, not merely the sample at the same array index.
 *
 * <p>{@link #timestampNanos()} is the latitude timestamp and is the route's time axis.
 * {@link #longitudeTimestampNanos()} is retained so pairing can be audited.
 */
public record RoutePoint(
        long timestampNanos,
        long latitudeTimestampNanos,
        long longitudeTimestampNanos,
        double latitude,
        double longitude,
        double altitude
) {
    public RoutePoint {
        if (!GeoDistance.validPosition(latitude, longitude)) {
            throw new IllegalArgumentException(
                    "RoutePoint requires a finite WGS84 position, got lat=" + latitude + " lon=" + longitude);
        }
    }

    public static RoutePoint of(long latitudeTimestampNanos,
                                long longitudeTimestampNanos,
                                double latitude,
                                double longitude,
                                double altitude) {
        return new RoutePoint(
                latitudeTimestampNanos,
                latitudeTimestampNanos,
                longitudeTimestampNanos,
                latitude,
                longitude,
                altitude);
    }
}
