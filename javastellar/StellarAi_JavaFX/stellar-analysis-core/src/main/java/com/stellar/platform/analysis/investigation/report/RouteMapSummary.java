package com.stellar.platform.analysis.investigation.report;

/**
 * Compact route context printed with a report map. Never a list of measurement filenames.
 */
public record RouteMapSummary(
        boolean projectWide,
        String scopeLabel,
        int measurementCount,
        int gpsCapableCount,
        int routeSegmentCount
) {
    public RouteMapSummary {
        scopeLabel = scopeLabel == null ? "" : scopeLabel;
        measurementCount = Math.max(0, measurementCount);
        gpsCapableCount = Math.max(0, gpsCapableCount);
        routeSegmentCount = Math.max(0, routeSegmentCount);
    }

    public static RouteMapSummary none() {
        return new RouteMapSummary(false, "", 0, 0, 0);
    }

    public boolean described() {
        return projectWide || !scopeLabel.isBlank();
    }
}
