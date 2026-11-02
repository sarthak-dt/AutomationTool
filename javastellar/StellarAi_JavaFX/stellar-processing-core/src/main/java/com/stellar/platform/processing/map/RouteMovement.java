package com.stellar.platform.processing.map;

/**
 * How much the vehicle actually moved during a recording.
 *
 * <p>A stationary recording is valid measurement data, not an error: an idle test, a soak, a bench
 * run with a GPS antenna attached. What is wrong is presenting it as an ordinary multi-kilometre
 * route, because the extent it implies is pure receiver noise and framing that noise asks for a
 * scale no basemap can render. Classifying the extent once lets the map frame it sensibly and say
 * what it is.
 */
public enum RouteMovement {

    /** Extent is within GPS receiver noise; there is no route, only a location. */
    STATIONARY("Vehicle remained stationary during this recording"),

    /** Extent is real but too small to read as a route at street scale. */
    MINIMAL("Very little vehicle movement detected"),

    NORMAL("");

    /** Receiver noise on a parked vehicle is metres, not tens of metres. */
    private static final double STATIONARY_EXTENT_METERS = 10.0;

    /** Below a city block there is nothing route-shaped to look at. */
    private static final double MINIMAL_EXTENT_METERS = 75.0;

    private static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    private final String message;

    RouteMovement(String message) {
        this.message = message;
    }

    /** Empty for {@link #NORMAL}; otherwise the wording for the route status line. */
    public String message() {
        return message;
    }

    public boolean isNormal() {
        return this == NORMAL;
    }

    public static RouteMovement classify(MapRouteModel route) {
        if (route == null || route.isEmpty()) {
            return NORMAL;
        }
        double extent = extentMeters(route);
        if (extent <= STATIONARY_EXTENT_METERS) {
            return STATIONARY;
        }
        return extent <= MINIMAL_EXTENT_METERS ? MINIMAL : NORMAL;
    }

    /**
     * Diagonal of the route's bounding box in metres.
     *
     * <p>Travelled distance is deliberately not used: a vehicle circling a test pad covers
     * kilometres inside a small box, which is a real route. Extent is what decides how the map has
     * to be framed.
     */
    public static double extentMeters(MapRouteModel route) {
        if (route == null || route.isEmpty()) {
            return 0;
        }
        double latitudeSpan = route.maxLatitude() - route.minLatitude();
        double longitudeSpan = route.maxLongitude() - route.minLongitude();
        double centerLatitude = (route.minLatitude() + route.maxLatitude()) * 0.5;
        double cosine = Math.max(0.05, Math.cos(Math.toRadians(centerLatitude)));
        double north = latitudeSpan * METERS_PER_DEGREE_LATITUDE;
        double east = longitudeSpan * METERS_PER_DEGREE_LATITUDE * cosine;
        return Math.hypot(north, east);
    }
}
