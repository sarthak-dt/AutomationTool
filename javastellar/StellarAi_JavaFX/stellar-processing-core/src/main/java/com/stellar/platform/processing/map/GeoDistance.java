package com.stellar.platform.processing.map;

/** Geodesic helpers for route distance. Haversine is accurate enough for vehicle-scale routes. */
public final class GeoDistance {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    /** @return great-circle distance in metres between two WGS84 coordinates. */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lon2 - lon1);
        double sinPhi = Math.sin(dPhi * 0.5);
        double sinLambda = Math.sin(dLambda * 0.5);
        double a = sinPhi * sinPhi + Math.cos(phi1) * Math.cos(phi2) * sinLambda * sinLambda;
        return 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static boolean validLatitude(double latitude) {
        return Double.isFinite(latitude) && latitude >= -90.0 && latitude <= 90.0;
    }

    public static boolean validLongitude(double longitude) {
        return Double.isFinite(longitude) && longitude >= -180.0 && longitude <= 180.0;
    }

    /**
     * Geographic validity only. Exactly 0°/0° is a legitimate WGS84 coordinate and is accepted;
     * NaN, Infinity and out-of-range values are rejected.
     */
    public static boolean validPosition(double latitude, double longitude) {
        return validLatitude(latitude) && validLongitude(longitude);
    }
}
