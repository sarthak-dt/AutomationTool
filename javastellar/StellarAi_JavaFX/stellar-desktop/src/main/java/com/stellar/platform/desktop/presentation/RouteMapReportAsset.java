package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.RouteMapSummary;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Static map produced for one report. The PNG contains pixels only. */
public final class RouteMapReportAsset {

    public static final String UNAVAILABLE = "Route map unavailable.";

    public record DetailImage(String fileName, byte[] png) {
    }

    private final byte[] png;
    private final RouteMapReportContext context;
    private final boolean failed;
    private final List<DetailImage> details;

    private RouteMapReportAsset(byte[] png, RouteMapReportContext context, boolean failed, List<DetailImage> details) {
        this.png = png;
        this.context = context;
        this.failed = failed;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static RouteMapReportAsset image(byte[] png, RouteMapReportContext context) {
        return new RouteMapReportAsset(png, context, false, List.of());
    }

    public static RouteMapReportAsset image(byte[] png, RouteMapReportContext context, List<DetailImage> details) {
        return new RouteMapReportAsset(png, context, false, details);
    }

    public static RouteMapReportAsset unavailable(RouteMapReportContext context) {
        return new RouteMapReportAsset(null, context, true, List.of());
    }

    public byte[] png() {
        return png;
    }

    public List<DetailImage> details() {
        return details;
    }

    public boolean hasImage() {
        return png != null && png.length > 0 && !failed;
    }

    public RouteMapReportContext context() {
        return context;
    }

    public EngineeringReportModel.RouteMapSection section(String imageReference) {
        ProjectRoute route = context.route();
        RouteMapSummary summary = new RouteMapSummary(
                context.projectWide(),
                context.projectWide() ? "All Measurements" : context.scopeLabel(),
                route.measurementCount(),
                route.gpsCapableCount(),
                route.routeSegmentCount());
        String distance = context.projectWide()
                ? String.format(Locale.US, "%.2f km", route.totalDistanceMeters() / 1000.0)
                : String.format(Locale.US, "%.1f km", route.totalDistanceMeters() / 1000.0);
        String samples = String.format(Locale.US, "%,d", route.gpsSampleCount());
        String start = "";
        String end = "";
        String duration = "";
        if (!context.projectWide() && !route.gpsSegments().isEmpty()) {
            RouteSegment one = route.gpsSegments().getFirst();
            start = EngineeringFormats.seconds(one.firstTimestampNanos());
            end = EngineeringFormats.seconds(one.lastTimestampNanos());
            duration = EngineeringFormats.duration(one.firstTimestampNanos(), one.lastTimestampNanos());
        }
        String reference = hasImage() && imageReference != null ? imageReference : "";
        String note = hasImage() ? context.chronologyStatus() : UNAVAILABLE;
        List<String> extra = new ArrayList<>();
        if (hasImage()) {
            for (DetailImage detail : details) {
                if (detail != null && detail.fileName() != null && !detail.fileName().isBlank()
                        && detail.png() != null && detail.png().length > 0) {
                    extra.add("assets/" + detail.fileName());
                }
            }
        }
        return new EngineeringReportModel.RouteMapSection(
                "Route Map", reference, note, start, end, duration, distance, samples, summary, extra);
    }
}
