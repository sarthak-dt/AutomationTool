package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.ui.graph.map.MapViewport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMapAutomaticReportTest {

    private static final String SECRET = "AIzaSyTESTKEY1234567890";
    private static final long SECOND = 1_000_000_000L;
    /**
     * 0.005 degrees every 10 seconds. Latitude movement is about 56 m/s. With the same
     * longitude step at 18.5 degrees the ground speed is about 280 km/h, below the 1,000 km/h
     * break. The same step once per second is about 2,000 km/h and is rejected.
     */
    private static final double CONTINUOUS_STEP_DEGREES = 0.005;
    private static final long SAMPLE_SPACING = 10L * SECOND;

    private String previousHome;
    private String previousAuto;
    private String previousAppPath;
    private String previousIsolate;

    @AfterEach
    void restore() {
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousAuto);
        restore("jpackage.app-path", previousAppPath);
        restore(AnalysisStorageIsolation.PROPERTY, previousIsolate);
    }

    @Test
    void preparedRouteIsIncludedWithoutMapEvidenceOrAStage(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        install(temp.resolve("home"), selected);
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        assertNotNull(context);
        assertTrue(context.projectWide());
        assertEquals(2, context.route().routeSegmentCount());
        assertTrue(context.route().totalDistanceMeters() > 0);
        assertTrue(context.route().totalDistanceMeters() < 20_000);

        RouteMapReportAsset asset = new RouteMapReportRenderer().render(context);
        assertTrue(asset.hasImage());
        String distance = String.format(java.util.Locale.US, "%.2f km", context.route().totalDistanceMeters() / 1000.0);

        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        Path html;
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.parse("2026-10-14T12:00:00Z"),
                    Instant.parse("2026-10-14T12:00:00Z")));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Release map").build());
            html = service.generateHtmlReport(store, saved, "engineer", null, asset);
        }
        String page = Files.readString(html);
        assertTrue(page.contains("<h2>Route Map</h2>"));
        assertEquals(1, page.split("<h2>Route Map</h2>", -1).length - 1);
        assertTrue(page.contains("assets/map-route.png"));
        assertTrue(page.contains(distance));
        assertTrue(page.contains("Independent segments"));
        assertFalse(page.contains("alpha.dat"));
        assertFalse(page.contains("beta.dat"));
        assertFalse(page.contains("<iframe"));
        assertFalse(page.toLowerCase(java.util.Locale.ROOT).contains("localhost"));
        assertFalse(page.contains("127.0.0.1"));
        assertFalse(page.contains(SECRET));
        assertFalse(page.contains("file:"));
        assertFalse(page.toLowerCase(java.util.Locale.ROOT).contains("appdata"));
        Path assetPath = html.getParent().resolve("assets").resolve(InvestigationStoragePaths.ROUTE_MAP_ASSET);
        assertTrue(Files.isRegularFile(assetPath));
        assertTrue(html.startsWith(selected.resolve("StellarAIAnalysis")));
        assertTrue(assetPath.startsWith(selected.resolve("StellarAIAnalysis")));
        assertFalse(new String(Files.readAllBytes(assetPath), StandardCharsets.ISO_8859_1).contains(SECRET));

        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            var row = reopened.listInvestigationReports(
                    html.getParent().getParent().getParent().getFileName().toString()).getFirst();
            assertEquals("COMPLETED", row.status());
            assertTrue(Files.isRegularFile(Path.of(row.destination())));
            assertTrue(Files.isRegularFile(assetPath));
        }
    }

    @Test
    void oneMeasurementRendersOnlyThatRoute() {
        RouteMapReportContext one = new ProjectRouteReportLoader().load(
                false, "src-a", "alpha.dat", 1, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        assertNotNull(one);
        assertFalse(one.projectWide());
        assertEquals(1, one.route().routeSegmentCount());
        assertEquals("alpha.dat", one.scopeLabel());
        long samples = one.route().gpsSampleCount();
        assertTrue(samples > 0);
        RouteMapReportContext all = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        assertTrue(all.route().gpsSampleCount() > samples);
        RouteMapReportAsset asset = new RouteMapReportRenderer().render(one);
        String html = com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer.render(
                com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder.build(
                        EngineeringInvestigation.builder("p1").name("One").build(),
                        new com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder.Context(
                                "StellarAI 1.0.1", "", null, List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(), List.of())
                                .withRouteMap(asset.section("assets/map-route.png"))));
        assertTrue(html.contains("alpha.dat"));
        assertFalse(html.contains("beta.dat"));
        assertTrue(html.contains("assets/map-route.png"));
    }

    @Test
    void noGpsOmitsTheRouteSection(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Plain").build());
            String page = Files.readString(service.generateHtmlReport(store, saved, "engineer", null, null));
            assertFalse(page.contains("<h2>Route Map</h2>"));
            assertFalse(page.contains("map-route.png"));
        }
    }

    @Test
    void renderFailureStillWritesTheReport(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        RouteMapReportAsset failed = new RouteMapReportRenderer().render(context, true);
        assertFalse(failed.hasImage());
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Fallback").build());
            String page = Files.readString(service.generateHtmlReport(store, saved, "engineer", null, failed));
            assertTrue(page.contains(RouteMapReportAsset.UNAVAILABLE));
            assertTrue(page.contains("Route Map"));
            assertFalse(page.contains("map-route.png"));
            assertFalse(page.contains(SECRET));
            assertFalse(page.contains("localhost"));
        }
    }

    @Test
    void reportMapIsHighResolutionAndKeepsEndpointsInside() throws Exception {
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        BufferedImage image = RouteMapReportRenderer.paint(context);
        assertTrue(image.getWidth() >= 1400);
        assertTrue(image.getHeight() >= 800);
        MapViewport viewport = RouteMapReportRenderer.fitted(context);
        for (var segment : context.route().gpsSegments()) {
            var model = segment.route().model();
            for (int index : new int[]{0, model.size() - 1}) {
                int x = (int) Math.round(viewport.x(model.longitude(index)));
                int y = (int) Math.round(viewport.y(model.latitude(index)));
                assertTrue(x >= 0 && x < image.getWidth(), "x=" + x);
                assertTrue(y >= 0 && y < image.getHeight(), "y=" + y);
            }
        }
        RouteMapReportContext one = new ProjectRouteReportLoader().load(
                false, "src-a", "alpha.dat", 1, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        BufferedImage single = RouteMapReportRenderer.paint(one);
        assertEquals(image.getWidth(), single.getWidth());
        assertEquals(image.getHeight(), single.getHeight());
        int background = image.getRGB(8, 8) & 0xFFFFFF;
        assertFalse(background == 0);
        java.nio.file.Path preview = java.nio.file.Path.of("target", "report-preview");
        java.nio.file.Files.createDirectories(preview);
        ImageIO.write(image, "png", preview.resolve("map-overview.png").toFile());
        ImageIO.write(single, "png", preview.resolve("map-single.png").toFile());
    }

    @Test
    void singleMeasurementInvestigationKeepsOnlyThatRoute() {
        com.stellar.platform.domain.catalog.SignalCatalogKey key = new SignalCatalogKey(
                "EngineSpeed", SignalSourceType.MF4_NATIVE, "ECU", 0, 0, "src-a", "");
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("p1")
                .signalKeyBlobs(List.of(GraphInvestigationCodec.encodeKey(key)))
                .build();
        assertEquals("src-a", ReportMapScope.singleMeasurementSource(investigation));
        ReportMapScope.Decision decision = ReportMapScope.decide(investigation, true, "", "", true);
        assertFalse(decision.projectWide());
        RouteMapReportContext one = new ProjectRouteReportLoader().load(
                false, decision.sourceId(), "alpha.dat", 1, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        RouteMapReportContext all = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        assertEquals(1, one.route().routeSegmentCount());
        assertEquals("alpha.dat", one.scopeLabel());
        assertTrue(one.route().gpsSampleCount() > 0);
        assertTrue(one.route().gpsSampleCount() < all.route().gpsSampleCount());
        assertTrue(one.route().totalDistanceMeters() > 0);
        assertTrue(one.route().totalDistanceMeters() < all.route().totalDistanceMeters());
        RouteMapReportAsset asset = new RouteMapReportRenderer().render(one);
        assertTrue(asset.details().isEmpty());
        BufferedImage image = RouteMapReportRenderer.paint(one);
        assertTrue(image.getWidth() >= 1400);
        assertTrue(image.getHeight() >= 800);
        String html = mapHtml(asset.section("assets/map-route.png"));
        assertTrue(html.contains("alpha.dat"));
        assertFalse(html.contains("beta.dat"));
        assertFalse(html.contains("map-route-detail"));
        assertEquals(1, html.split("src=\"assets/map-route.png\"", -1).length - 1);
        MapViewport singleView = RouteMapReportRenderer.fitted(one);
        assertTrue(singleView.centerLatitude() > 18 && singleView.centerLatitude() < 19);
        assertTrue(singleView.centerLongitude() > 73 && singleView.centerLongitude() < 74);
        assertTrue(singleView.y(48.12) < 0 || singleView.y(48.12) > singleView.height());
        assertTrue(singleView.longitudeAt(singleView.width()) - singleView.longitudeAt(0) < 1.0);
        assertOccupiesMostOfTheFrame(singleView, one);
    }

    @Test
    void allMeasurementsInvestigationKeepsOneAggregateMap() {
        com.stellar.platform.domain.catalog.SignalCatalogKey key = new SignalCatalogKey(
                "EngineSpeed", SignalSourceType.MF4_NATIVE, "ECU", 0, 0,
                com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE, "");
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("p1")
                .signalKeyBlobs(List.of(GraphInvestigationCodec.encodeKey(key)))
                .build();
        assertNull(ReportMapScope.singleMeasurementSource(investigation));
        assertTrue(ReportMapScope.decide(investigation, false, "src-a", "alpha.dat", true).projectWide());
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        assertEquals(2, context.route().routeSegmentCount());
        assertTrue(context.projectWide());
        RouteMapReportAsset asset = new RouteMapReportRenderer().render(context);
        assertTrue(asset.details().isEmpty());
        String html = mapHtml(asset.section("assets/map-route.png"));
        assertFalse(html.contains("map-route-detail"));
        assertFalse(html.contains("alpha.dat"));
        assertEquals(1, html.split("src=\"assets/map-route.png\"", -1).length - 1);
        assertTrue(html.contains("Independent segments"));
        MapViewport combined = RouteMapReportRenderer.fitted(context);
        assertTrue(combined.latitudeAt(0) > 48.0);
        assertTrue(combined.latitudeAt(combined.height()) < 18.6);
        assertTrue(combined.longitudeAt(0) < 18.0);
        assertTrue(combined.longitudeAt(combined.width()) > 73.0);
    }

    @Test
    void basemapFailureFallsBackToAReadableRoute(@TempDir Path temp) throws Exception {
        RouteMapReportContext one = new ProjectRouteReportLoader().load(
                false, "src-a", "alpha.dat", 1, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        ReportMapTiles.TileFetcher previous = ReportMapTiles.fetcher;
        try {
            ReportMapTiles.fetcher = (zoom, x, y) -> {
                throw new IOException("offline");
            };
            BufferedImage fallback = RouteMapReportRenderer.paint(one, true);
            assertTrue(fallback.getWidth() >= 1400);
            assertTrue(fallback.getHeight() >= 800);
            MapViewport viewport = RouteMapReportRenderer.fitted(one);
            var model = one.route().gpsSegments().getFirst().route().model();
            int onRoute = fallback.getRGB(
                    clamp((int) Math.round(viewport.x(model.longitude(model.size() / 2)))),
                    clampY((int) Math.round(viewport.y(model.latitude(model.size() / 2))))) & 0xFFFFFF;
            assertTrue(isRouteInk(onRoute), Integer.toHexString(onRoute));
            BufferedImage tile = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D tileGraphics = tile.createGraphics();
            tileGraphics.setColor(new Color(0xB7, 0xD1, 0x8A));
            tileGraphics.fillRect(0, 0, 16, 16);
            tileGraphics.dispose();
            ReportMapTiles.fetcher = (zoom, x, y) -> tile;
            BufferedImage tiled = RouteMapReportRenderer.paint(one, true);
            int land = tiled.getRGB(12, 12) & 0xFFFFFF;
            assertEquals(0xB7D18A, land);
            assertTrue(tiled.getWidth() >= 1400 && tiled.getHeight() >= 800);
        } finally {
            ReportMapTiles.fetcher = previous;
        }
    }

    @Test
    void reExportStoresRegeneratedGraphAndMap(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        RouteMapReportAsset map = new RouteMapReportRenderer().render(context);
        List<SignalReportImage> signals = SignalReportRenderer.render(List.of(
                new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{800, 2400, 1600}),
                new SignalReportRenderer.Track("VehicleSpeed", "km/h", new double[]{0, 80, 40}),
                new SignalReportRenderer.Track("Coolant", "°C", new double[]{70, 90, 85}),
                new SignalReportRenderer.Track("Throttle", "%", new double[]{0, 30, 10})), null);
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Reexport").build());
            Path legacy = temp.resolve("legacy-map.png");
            ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", legacy.toFile());
            Path zip = temp.resolve("export.zip");
            service.exportPackage(store, saved, zip, "engineer", null, map, signals);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                BufferedImage waveform = ImageIO.read(zf.getInputStream(zf.getEntry("report/assets/signals-waveform.png")));
                BufferedImage route = ImageIO.read(zf.getInputStream(zf.getEntry("report/assets/map-route.png")));
                assertTrue(waveform.getWidth() >= 1400);
                assertTrue(waveform.getHeight() >= 800);
                assertTrue(route.getWidth() >= 1600);
                assertTrue(route.getHeight() >= 900);
                assertTrue(route.getWidth() != 32);
                assertTrue(map.details().isEmpty());
                assertTrue(zf.getEntry("report/assets/map-route-detail-01.png") == null);
                String page = new String(zf.getInputStream(zf.getEntry("report/report.html")).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(page.contains("class=\"report-waveform\""));
                assertTrue(page.contains("class=\"report-map\""));
                assertFalse(page.contains("map-route-detail"));
                assertFalse(page.contains("img{max-width:100%;max-height:140mm;}"));
            }
        }
    }

    @Test
    void independentSegmentsAreNotJoinedOnTheImage() throws Exception {
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
        BufferedImage image = RouteMapReportRenderer.paint(context);
        MapViewport viewport = RouteMapReportRenderer.fitted(context);
        MapRouteModel first = context.route().gpsSegments().getFirst().route().model();
        int onRoute = image.getRGB(
                clamp((int) Math.round(viewport.x(first.longitude(first.size() / 2)))),
                clampY((int) Math.round(viewport.y(first.latitude(first.size() / 2))))) & 0xFFFFFF;
        assertTrue(isRouteInk(onRoute), Integer.toHexString(onRoute));
        MapRouteModel second = context.route().gpsSegments().get(1).route().model();
        int ax = clamp((int) Math.round(viewport.x(first.longitude(first.size() / 2))));
        int ay = clampY((int) Math.round(viewport.y(first.latitude(first.size() / 2))));
        int bx = clamp((int) Math.round(viewport.x(second.longitude(second.size() / 2))));
        int by = clampY((int) Math.round(viewport.y(second.latitude(second.size() / 2))));
        int between = image.getRGB((ax + bx) / 2, (ay + by) / 2) & 0xFFFFFF;
        assertFalse(isRouteInk(between), Integer.toHexString(between));
        assertTrue(ImageIO.write(image, "png", new java.io.ByteArrayOutputStream()));
    }

    private static void assertOccupiesMostOfTheFrame(MapViewport viewport, RouteMapReportContext context) {
        com.stellar.platform.desktop.ui.graph.map.MapViewportCalculator.Bounds driven =
                com.stellar.platform.desktop.ui.graph.map.MapViewportCalculator.of(
                        context.route().gpsSegments().stream()
                                .filter(segment -> segment.hasRoute())
                                .map(segment -> segment.route().model())
                                .toList());
        double widthFraction = (viewport.x(driven.maxLongitude()) - viewport.x(driven.minLongitude())) / viewport.width();
        double heightFraction = (viewport.y(driven.minLatitude()) - viewport.y(driven.maxLatitude())) / viewport.height();
        double occupancy = Math.max(widthFraction, heightFraction);
        assertTrue(occupancy >= 0.70 && occupancy <= 0.92, "occupancy " + occupancy);
    }

    private static String mapHtml(com.stellar.platform.analysis.investigation.report.EngineeringReportModel.RouteMapSection section) {
        return com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer.render(
                com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder.build(
                        EngineeringInvestigation.builder("p1").name("Map").build(),
                        new com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder.Context(
                                "StellarAI 1.0.1", "", null, List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(), List.of())
                                .withRouteMap(section)));
    }

    private static boolean isRouteInk(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        boolean route = red < 80 && blue > 180;
        boolean start = green > 140 && red < 80 && blue < 140;
        boolean end = red > 180 && green < 100 && blue < 100;
        return route || start || end;
    }

    private static int clamp(int x) {
        return Math.max(0, Math.min(RouteMapReportRenderer.WIDTH - 1, x));
    }

    private static int clampY(int y) {
        return Math.max(0, Math.min(RouteMapReportRenderer.HEIGHT - 1, y));
    }

    private static List<SignalCatalogEntry> catalog() {
        return List.of(
                entry("Latitude", "src-a", "alpha.dat", 18.50, 18.55),
                entry("Longitude", "src-a", "alpha.dat", 73.80, 73.85),
                entry("Latitude", "src-b", "beta.dat", 48.10, 48.15),
                entry("Longitude", "src-b", "beta.dat", 11.50, 11.55));
    }

    private static SignalCatalogEntry entry(String name, String id, String file, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
        return new SignalCatalogEntry(key, "deg", 8, 0L, 7 * SECOND, min, max, file, "");
    }

    private static Map<SignalCatalogKey, List<SignalSamplePoint>> samples() {
        return Map.of(
                key("Latitude", "src-a"), track(18.50, CONTINUOUS_STEP_DEGREES),
                key("Longitude", "src-a"), track(73.80, CONTINUOUS_STEP_DEGREES),
                key("Latitude", "src-b"), track(48.10, CONTINUOUS_STEP_DEGREES),
                key("Longitude", "src-b"), track(11.50, CONTINUOUS_STEP_DEGREES));
    }

    private static SignalCatalogKey key(String name, String id) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
    }

    private static List<SignalSamplePoint> track(double start, double step) {
        return java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> new SignalSamplePoint(i * SAMPLE_SPACING, start + i * step, null, null))
                .toList();
    }

    private void install(Path home, Path selected) throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        previousAppPath = System.getProperty("jpackage.app-path");
        previousIsolate = System.getProperty(AnalysisStorageIsolation.PROPERTY);
        Files.createDirectories(home);
        System.setProperty("stellarai.home", home.toAbsolutePath().normalize().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.setProperty("jpackage.app-path", "C:\\Program Files\\StellarAI\\StellarAI.exe");
        System.setProperty(AnalysisStorageIsolation.PROPERTY, "false");
        AnalysisStorageRoots.clear();
        AnalysisStorageRoots.setUserSelectedRoot(selected);
        StellarAiPaths.homeRoot();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class MemoryQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> samples;

        private MemoryQuery(Map<SignalCatalogKey, List<SignalSamplePoint>> samples) {
            this.samples = samples;
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return queryRange(key, startNanos, endNanos, key.sourceFile());
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
            if (sourceScope != null && !sourceScope.isBlank()
                    && !sourceScope.equalsIgnoreCase(key.sourceFile())) {
                return List.of();
            }
            return samples.getOrDefault(key, List.of());
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "deg";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return samples.getOrDefault(key, List.of()).size();
        }
    }
}
