package com.stellar.platform.processing.map;

import java.util.ArrayList;
import java.util.List;

/**
 * Project-wide Map Route view: independent {@link RouteSegment}s.
 * Distances are the sum of valid segments only — never a fabricated A→B connector.
 */
public final class ProjectRoute {

    private final List<RouteSegment> segments;
    private final int measurementCount;
    private final int gpsCapableCount;

    public ProjectRoute(List<RouteSegment> segments, int measurementCount, int gpsCapableCount) {
        this.segments = List.copyOf(segments == null ? List.of() : segments);
        this.measurementCount = Math.max(0, measurementCount);
        this.gpsCapableCount = Math.max(0, gpsCapableCount);
    }

    public static ProjectRoute empty() {
        return new ProjectRoute(List.of(), 0, 0);
    }

    public List<RouteSegment> segments() {
        return segments;
    }

    public List<RouteSegment> gpsSegments() {
        List<RouteSegment> out = new ArrayList<>();
        for (RouteSegment segment : segments) {
            if (segment.hasRoute()) {
                out.add(segment);
            }
        }
        return List.copyOf(out);
    }

    public int measurementCount() {
        return measurementCount;
    }

    public int gpsCapableCount() {
        return gpsCapableCount;
    }

    public int routeSegmentCount() {
        return gpsSegments().size();
    }

    public long gpsSampleCount() {
        long total = 0;
        for (RouteSegment segment : segments) {
            total += segment.sampleCount();
        }
        return total;
    }

    /** Sum of independent valid segment distances. No inter-file connectors. */
    public double totalDistanceMeters() {
        double total = 0;
        for (RouteSegment segment : segments) {
            if (segment.hasRoute()) {
                total += segment.distanceMeters();
            }
        }
        return total;
    }

    public boolean isEmpty() {
        return gpsSegments().isEmpty();
    }
}
