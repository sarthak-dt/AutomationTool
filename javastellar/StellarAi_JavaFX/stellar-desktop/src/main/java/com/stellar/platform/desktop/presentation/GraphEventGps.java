package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;

import java.nio.file.Path;
import java.util.Locale;

/**
 * GPS evidence for one graph event, stored in the existing bookmark observation.
 * A match names the original GNSS sample. It does not create a coordinate.
 */
public final class GraphEventGps {

    public static final String NOT_REQUESTED = "GPS not requested";
    public static final String REQUESTED = "GPS requested, unresolved";
    public static final String MATCHED = "GPS sample matched";
    public static final String NO_SAMPLE = "No valid matching sample";
    public static final String INCOMPATIBLE = "Source or clock incompatible";
    public static final String STALE = "GPS evidence stale or missing";

    public static final String KIND_NEAREST = "nearest";
    public static final String KIND_WINDOW = "first-in-window";

    /** Relative measurement times stay below this. Epoch nanoseconds sit above it. */
    static final long ABSOLUTE_THRESHOLD_NANOS = 1_000_000_000_000_000_000L;

    public record RouteView(
            MapRouteModel model,
            String sourceId,
            String fileName,
            String refusal,
            String latitudeChannel,
            String longitudeChannel
    ) {
        public RouteView {
            model = model == null ? MapRouteModel.empty() : model;
            sourceId = sourceId == null ? "" : sourceId;
            fileName = fileName == null ? "" : fileName;
            refusal = refusal == null ? "" : refusal;
            latitudeChannel = latitudeChannel == null ? "" : latitudeChannel;
            longitudeChannel = longitudeChannel == null ? "" : longitudeChannel;
        }

        public RouteView(MapRouteModel model, String sourceId, String fileName, String refusal) {
            this(model, sourceId, fileName, refusal, "", "");
        }

        public static RouteView none() {
            return new RouteView(MapRouteModel.empty(), "", "", "");
        }

        public boolean blocked() {
            return !refusal.isBlank();
        }
    }

    public record Evidence(
            String status,
            boolean located,
            long sampleTimestampNanos,
            double latitude,
            double longitude,
            long deltaNanos,
            String policy,
            String sourceId,
            String sourceFile,
            int sampleIndex,
            String kind,
            String statement,
            String latitudeChannel,
            String longitudeChannel
    ) {
    }

    private GraphEventGps() {
    }

    public static Evidence notRequested() {
        return new Evidence(NOT_REQUESTED, false, 0L, Double.NaN, Double.NaN, Long.MIN_VALUE,
                "", "", "", -1, "",
                "GPS evidence was not attached. No coordinate was invented.", "", "");
    }

    public static Evidence requested() {
        return new Evidence(REQUESTED, false, 0L, Double.NaN, Double.NaN, Long.MIN_VALUE,
                "", "", "", -1, "",
                "GPS evidence was requested. No GNSS sample has been linked. No coordinate was invented.", "", "");
    }

    public static Evidence review(EngineeringBookmarkMetadata bookmark, RouteView route) {
        if (bookmark == null) {
            return unavailable("The event was not found.");
        }
        if (route != null && route.blocked()) {
            return incompatible(route.refusal());
        }
        RouteView view = route == null ? RouteView.none() : route;
        var defined = EngineerEventDocuments.decode(bookmark);
        String eventFile = defined == null ? "" : defined.sourceFile();
        if (!sameMeasurement(bookmark.sourceId(), eventFile, view.sourceId(), view.fileName())) {
            return incompatible("This event and the GPS route are not the same measurement. "
                    + "Their clocks were not compared. No location was invented.");
        }
        if (view.model().isEmpty()) {
            return unavailable("GPS unavailable. This measurement has no GNSS route. No location was invented.");
        }
        if (view.latitudeChannel().isBlank() || view.longitudeChannel().isBlank()) {
            return incompatible("The GNSS latitude and longitude channels are not identified. "
                    + "No coordinate was copied from another signal.");
        }
        if (!sameTimeBasis(bookmark.timestampNanos(), view.model().timestampNanos(0))) {
            return incompatible("The event time and the GNSS samples are not on the same clock. "
                    + "No location was invented.");
        }
        Long end = defined == null ? null : defined.endNanos();
        if (end != null && end < bookmark.timestampNanos()) {
            return unavailable("The event window is not valid. No location was invented.");
        }
        String name = defined == null
                ? InvestigationEventCodec.titleOf(bookmark.observation())
                : defined.name();
        GnssEventCorrelation.Result located = GnssEventCorrelation.locate(
                view.model(), bookmark.timestampNanos(), end, name);
        if (!located.located()) {
            return unavailable(located.statement());
        }
        boolean window = end != null && end >= bookmark.timestampNanos();
        String kind = window ? KIND_WINDOW : KIND_NEAREST;
        String policy = located.policy();
        String sourceFile = view.fileName().isBlank() ? eventFile : view.fileName();
        String statement = describe(name, window, bookmark.timestampNanos(), end, located, policy, sourceFile, view);
        return new Evidence(MATCHED, true, located.sampleTimestampNanos(), located.latitude(), located.longitude(),
                located.deltaNanos(), policy, view.sourceId(), sourceFile, located.sampleIndex(), kind, statement,
                view.latitudeChannel(), view.longitudeChannel());
    }

    /**
     * A saved match stays verified only while that GNSS sample is still on this route.
     * An empty route leaves the saved text unchanged so a map that has not loaded is not called stale.
     */
    public static Evidence revalidate(EngineeringBookmarkMetadata bookmark, RouteView route, boolean routeChecked) {
        Evidence saved = read(bookmark);
        if (!MATCHED.equals(saved.status())) {
            return saved;
        }
        if (!routeChecked) {
            return saved;
        }
        if (route != null && route.blocked()) {
            return stale(saved, route.refusal());
        }
        RouteView view = route == null ? RouteView.none() : route;
        if (view.model().isEmpty()) {
            return stale(saved, "GPS evidence stale or missing. The route is not loaded. "
                    + "The saved coordinate was not treated as verified.");
        }
        if (!sameMeasurement(saved.sourceId(), saved.sourceFile(), view.sourceId(), view.fileName())
                && !sameMeasurement(bookmark.sourceId(), "", view.sourceId(), view.fileName())) {
            return stale(saved, "GPS evidence stale or missing. The saved sample belongs to a different measurement. "
                    + "It was not treated as verified.");
        }
        int index = saved.sampleIndex();
        MapRouteModel model = view.model();
        if (index < 0 || index >= model.size() || model.timestampNanos(index) != saved.sampleTimestampNanos()) {
            index = exactIndex(model, saved.sampleTimestampNanos());
        }
        if (index < 0) {
            return stale(saved, "GPS evidence stale or missing. The saved GNSS timestamp is not on the route. "
                    + "The saved coordinate was not treated as verified.");
        }
        if (!close(model.latitude(index), saved.latitude()) || !close(model.longitude(index), saved.longitude())) {
            return stale(saved, "GPS evidence stale or missing. The coordinate at the saved GNSS timestamp changed. "
                    + "It was not treated as verified.");
        }
        if (!saved.latitudeChannel().isBlank() && !view.latitudeChannel().isBlank()
                && !saved.latitudeChannel().equals(view.latitudeChannel())) {
            return stale(saved, "GPS evidence stale or missing. The latitude channel is not the saved GNSS channel. "
                    + "The saved coordinate was not treated as verified.");
        }
        if (!saved.longitudeChannel().isBlank() && !view.longitudeChannel().isBlank()
                && !saved.longitudeChannel().equals(view.longitudeChannel())) {
            return stale(saved, "GPS evidence stale or missing. The longitude channel is not the saved GNSS channel. "
                    + "The saved coordinate was not treated as verified.");
        }
        return saved;
    }

    public static Evidence read(EngineeringBookmarkMetadata bookmark) {
        if (bookmark == null || bookmark.observation() == null
                || !bookmark.observation().contains(EngineerEventDocuments.SENTINEL)) {
            return notRequested();
        }
        String status = EngineerEventDocuments.observationValue(bookmark, "gnssStatus");
        if (status.isBlank()) {
            String gnss = EngineerEventDocuments.observationValue(bookmark, "gnss");
            if (gnss.contains("was requested")) {
                return requested();
            }
            return notRequested();
        }
        return new Evidence(
                status,
                MATCHED.equals(status),
                parseLong(EngineerEventDocuments.observationValue(bookmark, "gnssSampleNanos")),
                parseDouble(EngineerEventDocuments.observationValue(bookmark, "gnssLat")),
                parseDouble(EngineerEventDocuments.observationValue(bookmark, "gnssLon")),
                parseDelta(EngineerEventDocuments.observationValue(bookmark, "gnssDeltaNanos")),
                EngineerEventDocuments.observationValue(bookmark, "gnssPolicy"),
                EngineerEventDocuments.observationValue(bookmark, "gnssSourceId"),
                EngineerEventDocuments.observationValue(bookmark, "gnssSourceFile"),
                (int) parseLong(EngineerEventDocuments.observationValue(bookmark, "gnssSampleIndex")),
                EngineerEventDocuments.observationValue(bookmark, "gnssKind"),
                EngineerEventDocuments.observationValue(bookmark, "gnss"),
                EngineerEventDocuments.observationValue(bookmark, "gnssLatChannel"),
                EngineerEventDocuments.observationValue(bookmark, "gnssLonChannel"));
    }

    public static String write(String observation, Evidence evidence) {
        Evidence stored = evidence == null ? notRequested() : evidence;
        boolean attached = MATCHED.equals(stored.status());
        return EngineerEventDocuments.withGps(
                observation,
                attached,
                stored.statement(),
                stored.status(),
                attached ? Long.toString(stored.sampleTimestampNanos()) : "",
                attached ? coordinate(stored.latitude()) : "",
                attached ? coordinate(stored.longitude()) : "",
                attached && stored.deltaNanos() != Long.MIN_VALUE && KIND_NEAREST.equals(stored.kind())
                        ? Long.toString(stored.deltaNanos()) : "",
                stored.policy() == null ? "" : stored.policy(),
                stored.sourceId() == null ? "" : stored.sourceId(),
                stored.sourceFile() == null ? "" : stored.sourceFile(),
                attached && stored.sampleIndex() >= 0 ? Integer.toString(stored.sampleIndex()) : "",
                stored.kind() == null ? "" : stored.kind(),
                attached ? stored.latitudeChannel() : "",
                attached ? stored.longitudeChannel() : "");
    }

    public static int exactIndex(MapRouteModel model, long timestampNanos) {
        if (model == null || model.isEmpty()) {
            return -1;
        }
        int index = model.indexAtOrAfter(timestampNanos);
        if (index < 0 || index >= model.size() || model.timestampNanos(index) != timestampNanos) {
            return -1;
        }
        while (index > 0 && model.timestampNanos(index - 1) == timestampNanos) {
            index--;
        }
        return index;
    }

    static boolean sameMeasurement(String eventSourceId, String eventFile, String routeSourceId, String routeFile) {
        boolean eventBlank = blank(eventSourceId) && fileName(eventFile).isBlank();
        if (eventBlank) {
            return true;
        }
        if (!blank(eventSourceId) && !blank(routeSourceId)) {
            return eventSourceId.equals(routeSourceId);
        }
        String left = fileName(eventFile);
        String right = fileName(routeFile);
        return !left.isBlank() && left.equalsIgnoreCase(right);
    }

    static boolean sameTimeBasis(long eventNanos, long sampleNanos) {
        return absolute(eventNanos) == absolute(sampleNanos);
    }

    public static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? path.trim() : name.toString();
        } catch (RuntimeException ex) {
            return path.trim();
        }
    }

    private static String describe(
            String name,
            boolean window,
            long startNanos,
            Long endNanos,
            GnssEventCorrelation.Result located,
            String policy,
            String sourceFile,
            RouteView route
    ) {
        StringBuilder text = new StringBuilder();
        text.append(window ? "First GNSS sample inside the window" : "Nearest GNSS sample");
        text.append(" for ").append(name == null || name.isBlank() ? "the event" : name).append(". ");
        if (window) {
            text.append("The marker is that sample, not the whole event. ");
            text.append("Event ").append(EngineeringFormats.seconds(startNanos));
            if (endNanos != null) {
                text.append(" to ").append(EngineeringFormats.seconds(endNanos));
            }
            text.append(". ");
        } else if (located.deltaNanos() == 0L) {
            text.append("The GNSS sample is at the event timestamp. ");
        } else {
            text.append("Time difference ").append(EngineeringFormats.seconds(Math.abs(located.deltaNanos())));
            text.append(located.deltaNanos() > 0L ? " after the event. " : " before the event. ");
            text.append("The sample is not at the event timestamp. ");
        }
        text.append("GNSS sample ").append(EngineeringFormats.seconds(located.sampleTimestampNanos())).append(". ");
        text.append("Latitude ").append(coordinate(located.latitude())).append(". ");
        text.append("Longitude ").append(coordinate(located.longitude())).append(". ");
        text.append("Policy: ").append(policy).append(". ");
        if (sourceFile != null && !sourceFile.isBlank()) {
            text.append("Source ").append(fileName(sourceFile)).append(". ");
        }
        text.append("GNSS channels ").append(route.latitudeChannel()).append(" and ")
                .append(route.longitudeChannel()).append(". ");
        text.append("The coordinate was not interpolated.");
        return text.toString();
    }

    private static Evidence incompatible(String statement) {
        return new Evidence(INCOMPATIBLE, false, 0L, Double.NaN, Double.NaN, Long.MIN_VALUE,
                "", "", "", -1, "", statement, "", "");
    }

    private static Evidence unavailable(String statement) {
        return new Evidence(NO_SAMPLE, false, 0L, Double.NaN, Double.NaN, Long.MIN_VALUE,
                "", "", "", -1, "", statement, "", "");
    }

    private static Evidence stale(Evidence saved, String statement) {
        String savedCoordinate = "";
        if (Double.isFinite(saved.latitude()) && Double.isFinite(saved.longitude())) {
            savedCoordinate = " Saved latitude " + coordinate(saved.latitude())
                    + " and longitude " + coordinate(saved.longitude()) + ".";
        }
        return new Evidence(STALE, false, saved.sampleTimestampNanos(), saved.latitude(), saved.longitude(),
                saved.deltaNanos(), saved.policy(), saved.sourceId(), saved.sourceFile(), saved.sampleIndex(),
                saved.kind(), statement + savedCoordinate, saved.latitudeChannel(), saved.longitudeChannel());
    }

    private static boolean absolute(long nanos) {
        return nanos >= ABSOLUTE_THRESHOLD_NANOS || nanos <= -ABSOLUTE_THRESHOLD_NANOS;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean close(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= 1e-5d;
    }

    private static String coordinate(double value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static long parseDelta(String text) {
        if (text == null || text.isBlank()) {
            return Long.MIN_VALUE;
        }
        return parseLong(text);
    }

    private static double parseDouble(String text) {
        if (text == null || text.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
