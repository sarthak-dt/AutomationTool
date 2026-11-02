package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the workstation Test/Lavasa project store when present. Not shipped as a fixture.
 */
class MapRouteLavasaDatasetAuditTest {

    private static final Path PROJECT = Path.of(
            "C:\\Users\\ADMIN\\Documents\\SignalFiles111\\StellarAIAnalysis\\projects",
            "9bb6321e-f6a2-4f28-82f0-36e64d7f2339");
    private static final String SELECTED_SOURCE = "76aab81b-43c4-4d1d-99fa-80d1204606b3";

    static boolean storePresent() {
        if (Boolean.getBoolean("stellarai.test.isolate")) {
            return false;
        }
        return Files.isDirectory(PROJECT.resolve("signal-store").resolve(SELECTED_SOURCE));
    }

    @Test
    @EnabledIf("storePresent")
    void selectedMeasurementRouteIsPhysicallyPlausibleAndNotProjectMerged() throws IOException {
        List<GraphSignalSessionStore> opened = new ArrayList<>();
        try {
            List<ProjectMergedSignalStore.Member> members = openMembers(opened);
            ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(members, "project", "merged");
            GraphSeriesQuery query = merged.graphQuery();

            GpsDetectionResult detected = new GpsSignalDiscoveryService().discover(merged.snapshot().entries());
            Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalDiscoveryService.toRoleMap(detected));
            assertTrue(GpsSignalRoleResolver.hasRoute(roles), detected.summary());

            MapRouteService service = new MapRouteService(query);
            MapRoute unscoped = service.prepare(
                    new MapRouteCacheKey("lavasa", "PROJECT", 1L, roles), Long.MIN_VALUE, Long.MAX_VALUE);
            MapRoute scoped = service.prepare(
                    new MapRouteCacheKey("lavasa", SELECTED_SOURCE, 1L, roles), Long.MIN_VALUE, Long.MAX_VALUE);

            ValidatedRoute scopedValidated = MapRouteBuilder.validateAndBuild(
                    query.queryRange(roles.get(GpsSignalRole.LATITUDE), Long.MIN_VALUE, Long.MAX_VALUE, SELECTED_SOURCE),
                    query.queryRange(roles.get(GpsSignalRole.LONGITUDE), Long.MIN_VALUE, Long.MAX_VALUE, SELECTED_SOURCE),
                    roles.get(GpsSignalRole.ALTITUDE) == null
                            ? List.of()
                            : query.queryRange(roles.get(GpsSignalRole.ALTITUDE), Long.MIN_VALUE, Long.MAX_VALUE, SELECTED_SOURCE));

            System.out.println("LAVASA unscoped " + describe(unscoped));
            System.out.println("LAVASA scoped   " + describe(scoped) + " · " + scopedValidated.diagnostics().summary());

            assertTrue(scoped.model().size() > 100, "selected file must have a real GPS track");
            assertTrue(scoped.model().totalDistanceMeters() < 50_000,
                    "selected-file distance must be a city drive, got "
                            + scoped.model().totalDistanceMeters() + " m");
            RouteRangeMetrics metrics = RouteRangeMetrics.compute(scoped.model(), 0, scoped.model().size() - 1, null);
            assertNotNull(metrics.averageSpeedKph());
            assertTrue(metrics.averageSpeedKph() < 150.0, "avg " + metrics.averageSpeedKph());
            assertTrue(metrics.maxSpeedKph() == null || metrics.maxSpeedKph() < 400.0,
                    "max " + metrics.maxSpeedKph());
            assertTrue(metrics.distanceKilometers() < 50.0);

            DriveSegmentSummary analysis = new DriveSegmentAnalysisService().analyze(
                    new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                            scoped.model(), 0, scoped.model().size() - 1, false, null, null,
                            List.of(), List.of(), null, 0));
            assertEquals(scoped.model().totalDistanceMeters(), analysis.motion().range().distanceMeters(), 1e-6);
            assertEquals(scoped.model().firstTimestampNanos(), analysis.startTimestampNanos());
            assertEquals(scoped.model().lastTimestampNanos(), analysis.endTimestampNanos());

            assertTrue(unscoped.model().size() >= scoped.model().size(), "merged overlay may contain more points");
        } finally {
            for (GraphSignalSessionStore store : opened) {
                try {
                    store.close();
                } catch (RuntimeException ignored) {
                    // best-effort close of workstation artifacts
                }
            }
        }
    }

    private static String describe(MapRoute route) {
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return "empty";
        }
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, model.size() - 1, null);
        int breaks = 0;
        for (int i = 1; i < model.size(); i++) {
            if (model.segmentBreakBefore(i)) {
                breaks++;
            }
        }
        return "n=" + model.size()
                + " distKm=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.distanceKilometers())
                + " avgKph=" + format(metrics.averageSpeedKph())
                + " maxKph=" + format(metrics.maxSpeedKph())
                + " breaks=" + breaks
                + " first=" + String.format(java.util.Locale.ROOT, "t=%.3fs lat=%.7f lon=%.7f",
                model.firstTimestampNanos() / 1e9, model.latitude(0), model.longitude(0))
                + " last=" + String.format(java.util.Locale.ROOT, "t=%.3fs lat=%.7f lon=%.7f",
                model.lastTimestampNanos() / 1e9,
                model.latitude(model.size() - 1), model.longitude(model.size() - 1));
    }

    private static String format(Double value) {
        return value == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static List<ProjectMergedSignalStore.Member> openMembers(List<GraphSignalSessionStore> opened)
            throws IOException {
        Path signalStore = PROJECT.resolve("signal-store");
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        try (DirectoryStream<Path> sources = Files.newDirectoryStream(signalStore)) {
            for (Path source : sources) {
                if (!Files.isDirectory(source)) {
                    continue;
                }
                Path artifact = findArtifact(source);
                if (artifact == null) {
                    continue;
                }
                GraphSignalSessionStore store = GraphSignalSessionStore.openExisting(artifact);
                opened.add(store);
                String sourceId = source.getFileName().toString();
                members.add(new ProjectMergedSignalStore.Member(
                        store, sourceId, "412943c0-58bf-471d-a7bb-ebbe80cf1ef5", sourceId, "dbc"));
            }
        }
        return members;
    }

    private static Path findArtifact(Path sourceDir) throws IOException {
        try (DirectoryStream<Path> channels = Files.newDirectoryStream(sourceDir)) {
            for (Path channel : channels) {
                if (!Files.isDirectory(channel)) {
                    continue;
                }
                try (DirectoryStream<Path> artifacts = Files.newDirectoryStream(channel)) {
                    for (Path artifact : artifacts) {
                        if (Files.isRegularFile(artifact.resolve("series-index.v1"))) {
                            return artifact;
                        }
                    }
                }
            }
        }
        return null;
    }
}
