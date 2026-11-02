package com.stellar.platform.desktop.presentation;

import com.stellar.platform.processing.map.ProjectRoute;

/** Route already prepared for a report. Holds no credentials and no tile URLs. */
public record RouteMapReportContext(
        boolean projectWide,
        String scopeLabel,
        ProjectRoute route,
        String chronologyStatus
) {

    public RouteMapReportContext(boolean projectWide, String scopeLabel, ProjectRoute route) {
        this(projectWide, scopeLabel, route, "");
    }

    public RouteMapReportContext {
        scopeLabel = scopeLabel == null ? "" : scopeLabel;
        route = route == null ? ProjectRoute.empty() : route;
        chronologyStatus = chronologyStatus == null ? "" : chronologyStatus;
    }

    public boolean hasRoute() {
        return !route.isEmpty();
    }
}
