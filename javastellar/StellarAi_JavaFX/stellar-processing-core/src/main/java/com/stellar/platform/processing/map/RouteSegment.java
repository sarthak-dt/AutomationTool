package com.stellar.platform.processing.map;

import java.util.Objects;

/**
 * One measurement's GPS route. Independent of every other file — never a concatenated session.
 */
public final class RouteSegment {

    private final String measurementId;
    private final String displayName;
    private final MapRoute route;

    public RouteSegment(String measurementId, String displayName, MapRoute route) {
        this.measurementId = measurementId == null ? "" : measurementId.trim();
        this.displayName = displayName == null || displayName.isBlank() ? this.measurementId : displayName.trim();
        this.route = route == null ? MapRoute.emptyRoute() : route;
    }

    public String measurementId() {
        return measurementId;
    }

    public String displayName() {
        return displayName;
    }

    public MapRoute route() {
        return route;
    }

    public boolean hasRoute() {
        return !route.isEmpty();
    }

    public int sampleCount() {
        return route.model().size();
    }

    public double distanceMeters() {
        return route.model().totalDistanceMeters();
    }

    public long firstTimestampNanos() {
        return route.isEmpty() ? 0L : route.model().firstTimestampNanos();
    }

    public long lastTimestampNanos() {
        return route.isEmpty() ? 0L : route.model().lastTimestampNanos();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RouteSegment that)) {
            return false;
        }
        return measurementId.equals(that.measurementId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(measurementId);
    }
}
