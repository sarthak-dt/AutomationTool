package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.analysis.investigation.report.RouteMapSnapshot;

/** One static capture of the route already shown on the map. */
public record RouteMapCapture(byte[] png, RouteMapSnapshot facts, String failure) {

    public static RouteMapCapture noRoute() {
        return new RouteMapCapture(null, null, "");
    }

    public boolean hasRoute() {
        return facts != null;
    }

    public boolean imageCaptured() {
        return png != null && png.length > 0;
    }
}
