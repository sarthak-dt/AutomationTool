package com.stellar.platform.processing.map;

/**
 * Engineering role a catalog signal plays for map/route and location analysis.
 *
 * <p>A role is metadata derived from an existing catalog entry. It never replaces or mutates the
 * original signal name: callers keep the source name for display and provenance and use the role
 * only to answer "which signal carries latitude here".
 *
 * <p>Roles are resolved once to canonical {@code SignalCatalogKey}s; the route engine never
 * works with UI display strings.
 */
public enum GpsSignalRole {
    LATITUDE("Latitude"),
    LONGITUDE("Longitude"),
    ALTITUDE("Altitude"),
    GPS_SPEED("GPS Speed"),
    COURSE("Course Over Ground"),
    DISTANCE("Ground Distance"),
    SATELLITE_COUNT("Number of Satellites"),
    GPS_QUALITY("GPS Quality"),
    HORIZONTAL_ACCURACY("Horizontal Accuracy"),
    VERTICAL_ACCURACY("Vertical Accuracy");

    private final String displayName;

    GpsSignalRole(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable role label. The measurement's own signal name remains authoritative. */
    public String displayName() {
        return displayName;
    }

    /** Latitude and longitude are the only roles required to draw a route. */
    public boolean required() {
        return this == LATITUDE || this == LONGITUDE;
    }
}
