package com.stellar.platform.processing.map;

/**
 * Resolves a graph cursor onto one GPS route segment.
 * A relative timestamp is never used to search a different measurement.
 * Coordinates and timestamps are the stored sample; nothing is interpolated or rewritten.
 */
public final class MeasurementGpsSync {

    public enum Status {
        MATCHED,
        MISSING_GPS,
        NO_MEASUREMENT
    }

    public record Match(
            Status status,
            String measurementId,
            String displayName,
            int sampleIndex,
            long sampleTimestampNanos,
            double latitude,
            double longitude
    ) {
        public static Match unmatched(Status status, String measurementId, String displayName) {
            return new Match(status, measurementId == null ? "" : measurementId, displayName == null ? "" : displayName,
                    -1, 0L, Double.NaN, Double.NaN);
        }
    }

    private MeasurementGpsSync() {
    }

    public static int fileIndex(java.util.List<String> measurementIds, String measurementId) {
        if (measurementIds == null || measurementId == null || measurementId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < measurementIds.size(); i++) {
            String id = measurementIds.get(i);
            if (id != null && id.equalsIgnoreCase(measurementId)) {
                return i;
            }
        }
        return -1;
    }

    public static Match resolve(ProjectRoute route, String measurementId, long relativeTimestampNanos) {
        if (measurementId == null || measurementId.isBlank()) {
            return Match.unmatched(Status.NO_MEASUREMENT, "", "");
        }
        RouteSegment segment = segment(route, measurementId);
        if (segment == null || !segment.hasRoute()) {
            String label = segment == null ? measurementId : segment.displayName();
            return Match.unmatched(Status.MISSING_GPS, measurementId, label);
        }
        MapRouteModel model = segment.route().model();
        int index = model.nearestIndexByTime(relativeTimestampNanos);
        if (index < 0) {
            return Match.unmatched(Status.MISSING_GPS, segment.measurementId(), segment.displayName());
        }
        return new Match(
                Status.MATCHED,
                segment.measurementId(),
                segment.displayName(),
                index,
                model.timestampNanos(index),
                model.latitude(index),
                model.longitude(index));
    }

    public static RouteSegment segment(ProjectRoute route, String measurementId) {
        if (route == null || measurementId == null || measurementId.isBlank()) {
            return null;
        }
        for (RouteSegment candidate : route.segments()) {
            if (candidate != null && measurementId.equalsIgnoreCase(candidate.measurementId())) {
                return candidate;
            }
        }
        return null;
    }
}
