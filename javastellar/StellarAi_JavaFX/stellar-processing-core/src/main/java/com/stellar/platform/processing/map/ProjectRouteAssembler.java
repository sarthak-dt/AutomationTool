package com.stellar.platform.processing.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a project-wide route from independently prepared measurement routes.
 * Never invents a connecting line or a common relative timeline.
 */
public final class ProjectRouteAssembler {

    private ProjectRouteAssembler() {
    }

    public record PreparedMeasurement(String measurementId, String displayName, MapRoute route) {
        public PreparedMeasurement {
            measurementId = measurementId == null ? "" : measurementId.trim();
            displayName = displayName == null || displayName.isBlank() ? measurementId : displayName.trim();
            route = route == null ? MapRoute.emptyRoute() : route;
        }
    }

    /**
     * {@code prepared} must already be in deterministic project/source order.
     * Absolute recording-start is not inferred here.
     */
    public static ProjectRoute assemble(List<PreparedMeasurement> prepared, int measurementCount) {
        List<PreparedMeasurement> rows = prepared == null ? List.of() : prepared;
        List<RouteSegment> segments = new ArrayList<>();
        int gpsCapable = 0;
        for (PreparedMeasurement row : rows) {
            Objects.requireNonNull(row, "prepared measurement");
            RouteSegment segment = new RouteSegment(row.measurementId(), row.displayName(), row.route());
            segments.add(segment);
            if (segment.hasRoute()) {
                gpsCapable++;
            }
        }
        return new ProjectRoute(segments, Math.max(measurementCount, rows.size()), gpsCapable);
    }

    /** Independent segment distances only — never last(A)→first(B). */
    public static double independentDistanceMeters(ProjectRoute route) {
        return route == null ? 0 : route.totalDistanceMeters();
    }

    public static boolean connectsAdjacentFiles(ProjectRoute route) {
        return false;
    }
}
