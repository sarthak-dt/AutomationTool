package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import com.stellar.platform.desktop.ui.UiFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportEvidenceModelTest {

    private String previousHome;
    private String previousAuto;
    private String previousAppPath;
    private String previousIsolate;

    @AfterEach
    void restore() {
        DesktopInvestigationService.reportCheckpoint = stage -> {
        };
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousAuto);
        restore("jpackage.app-path", previousAppPath);
        restore(AnalysisStorageIsolation.PROPERTY, previousIsolate);
    }

    @Test
    void selectedSignalsRenderAWaveformPage() throws Exception {
        List<SignalReportImage> images = SignalReportRenderer.render(List.of(
                new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{800, 1200, 2400, 1800}),
                new SignalReportRenderer.Track("VehicleSpeed", "km/h", new double[]{0, 20, 45, 30})), null);
        assertEquals(1, images.size());
        assertEquals("signals-waveform.png", images.getFirst().fileName());
        assertTrue(images.getFirst().hasImage());
        assertTrue(images.getFirst().title().contains("EngineSpeed"));
        assertTrue(images.getFirst().title().contains("VehicleSpeed"));
        String html = htmlWith(SignalReportRenderer.sections(images), null, List.of());
        assertTrue(html.contains("<h2>Signal Analysis</h2>"));
        assertTrue(html.contains("assets/signals-waveform.png"));
        assertTrue(html.contains("max-width:100%"));
        assertFalse(html.contains("sidebar"));
    }

    @Test
    void manySignalsSplitAcrossPagesWithoutDroppingNames() {
        List<SignalReportRenderer.Track> tracks = new ArrayList<>();
        for (int i = 1; i <= SignalReportRenderer.TRACKS_PER_IMAGE + 1; i++) {
            tracks.add(new SignalReportRenderer.Track("Signal" + i, "", new double[]{i, i + 1.0}));
        }
        List<SignalReportImage> images = SignalReportRenderer.render(tracks, null);
        assertEquals(2, images.size());
        assertEquals("signals-waveform-01.png", images.get(0).fileName());
        assertEquals("signals-waveform-02.png", images.get(1).fileName());
        assertTrue(images.get(1).title().contains("Signal" + (SignalReportRenderer.TRACKS_PER_IMAGE + 1)));
    }

    @Test
    void noSelectedSignalsOmitTheSection() {
        assertTrue(SignalReportRenderer.sections(List.of()).isEmpty());
        String html = htmlWith(List.of(), null, List.of());
        assertFalse(html.contains("<h2>Signal Analysis</h2>"));
        assertFalse(html.contains("signals-waveform"));
    }

    @Test
    void noGpsOmitsTheRouteSection() {
        String html = htmlWith(List.of(), null, List.of());
        assertFalse(html.contains("<h2>Route Map</h2>"));
        assertFalse(html.contains("map-route.png"));
    }

    @Test
    void failedVisualKeepsTheReportValid() {
        List<SignalReportImage> failed = List.of(new SignalReportImage(
                "signals-waveform.png", null, "Signal Analysis — EngineSpeed", true));
        String html = htmlWith(SignalReportRenderer.sections(failed), null, List.of());
        assertTrue(html.contains("Signal analysis unavailable."));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("Engineering Analysis Report"));
    }

    @Test
    void threeScreenshotsAndADuplicateRoute(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        byte[] mapPng = colored(Color.BLUE);
        byte[] other = colored(Color.RED);
        byte[] third = colored(Color.GREEN);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Evidence").build());
            service.writeSnapshotPng(saved.projectId(), saved.investigationId(), "same", mapPng);
            service.writeSnapshotPng(saved.projectId(), saved.investigationId(), "wave", other);
            service.writeSnapshotPng(saved.projectId(), saved.investigationId(), "diag", third);
            service.writeSnapshotPng(saved.projectId(), saved.investigationId(), "missing", new byte[0]);
            Files.deleteIfExists(InvestigationStoragePaths.snapshot(
                    AnalysisStorageRoots.projectArtifactsRoot(saved.projectId()),
                    saved.investigationId(), "missing"));
            EngineeringInvestigation with = saved.toBuilder().evidence(List.of(
                    shot(saved.investigationId(), "e1", "same", 0, "Route Map"),
                    shot(saved.investigationId(), "e2", "wave", 1, "Signal view"),
                    shot(saved.investigationId(), "e3", "diag", 2, "Diagnostics"),
                    shot(saved.investigationId(), "e4", "missing", 3, "Analytics"))).build();
            saved = service.save(store, with);
            RouteMapReportContext context = new ProjectRouteReportLoader().load(
                    true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
            RouteMapReportAsset map = RouteMapReportAsset.image(mapPng, context);
            List<SignalReportImage> signals = SignalReportRenderer.render(List.of(
                    new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{1, 2, 3})), null);
            Path html = service.generateHtmlReport(store, saved, "engineer", null, map, signals);
            String page = Files.readString(html);
            assertTrue(page.contains("assets/map-route.png"));
            assertTrue(page.contains("assets/signals-waveform.png"));
            assertTrue(page.contains("assets/evidence-001.png"));
            assertTrue(page.contains("assets/evidence-002.png"));
            assertTrue(page.contains("Signal view"));
            assertTrue(page.contains("Diagnostics"));
            assertTrue(page.contains("Evidence image unavailable."));
            assertFalse(page.contains("assets/evidence-003.png"), page);
            assertEquals(1, page.split("src=\"assets/map-route.png\"", -1).length - 1);
            assertFalse(page.contains(html.toString()));
            assertFalse(page.toLowerCase().contains("localhost"));
            long size = ReportPackageSize.bytes(html);
            long walked = Files.walk(html.getParent()).filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception ex) {
                    return 0L;
                }
            }).sum();
            assertEquals(walked, size);
            assertTrue(size > Files.size(html));
            assertFalse(UiFormat.bytes(size).startsWith("0"));
            Path original = Files.readAllBytes(html).length > 0 ? html : html;
            byte[] before = Files.readAllBytes(original);
            Path zip = temp.resolve("export.zip");
            service.exportPackage(store, saved, zip, "engineer", null, map, signals);
            assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(original));
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                assertTrue(zf.getEntry("report/report.html") != null);
                assertTrue(zf.getEntry("report/assets/map-route.png") != null);
                assertTrue(zf.getEntry("report/assets/signals-waveform.png") != null);
                String exported = new String(zf.getInputStream(zf.getEntry("report/report.html")).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(exported.contains("assets/map-route.png"));
                assertTrue(exported.contains("assets/signals-waveform.png"));
                assertTrue(exported.contains("assets/evidence-001.png"));
                assertFalse(exported.contains("localhost"));
            }
        }
    }

    @Test
    void oldReportWithoutPersistedSignalsDoesNotInventAWaveform() {
        EngineeringInvestigation old = EngineeringInvestigation.builder("p1")
                .signalKeyBlobs(List.of("VehicleSpeed|DBC_CAN"))
                .build();
        assertTrue(new SignalReportLoader().load(null, old, true, null).isEmpty());
        String html = htmlWith(List.of(), null, List.of());
        assertFalse(html.contains("signals-waveform"));
    }

    @Test
    void persistedKeyWithoutSamplesDoesNotInventPixels() {
        SignalCatalogKey key = new SignalCatalogKey(
                "EngineSpeed", SignalSourceType.MF4_NATIVE, "ECU", 0, 0, "", "");
        EngineeringInvestigation old = EngineeringInvestigation.builder("p1")
                .signalKeyBlobs(List.of(GraphInvestigationCodec.encodeKey(key)))
                .build();
        List<SignalReportImage> images = new SignalReportLoader().load(null, old, true, null);
        assertEquals(1, images.size());
        assertFalse(images.getFirst().hasImage());
        String html = htmlWith(SignalReportRenderer.sections(images), null, List.of());
        assertTrue(html.contains("Signal analysis unavailable."));
        assertFalse(html.contains("signals-waveform.png"));
    }

    @Test
    void cancelDuringMapRenderingRemovesThePartialReport(@TempDir Path temp) throws Exception {
        cancelAt(temp, "map");
    }

    @Test
    void cancelDuringSignalRenderingRemovesThePartialReport(@TempDir Path temp) throws Exception {
        cancelAt(temp, "signals");
    }

    @Test
    void directProcessingArtifactsCountAsProcessedMeasurements(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Dat", Instant.now(), Instant.now()));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run-1", "p1", "COMPLETED", false, 3, Instant.now(), Instant.now(),
                    "Direct processing completed."));
            for (int i = 1; i <= 3; i++) {
                store.upsertFile(new ProjectFileMetadata(
                        "p1", "src-" + i, "C:/data/part-" + i + ".dat", "MEASUREMENT_DAT",
                        2048, "", "", "", "", false, true, false));
                store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                        "art-" + i, "p1", "src-" + i, 0, "", "run-1",
                        ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                        "", "projects/p1/src-" + i, "1", "fp", "", "",
                        30, 1000, 0L, 1_000_000_000L,
                        ProcessingArtifactMetadata.STATUS_VALID, Instant.now(),
                        "NOT_APPLICABLE", "SUCCESS", "", ""));
            }
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Direct").build());
            String page = Files.readString(service.generateHtmlReport(store, saved, "engineer", null, null));
            assertTrue(page.contains(">Processed measurements</th><td>3</td>"));
            assertTrue(page.contains(">Successful measurements</th><td>3</td>"));
            assertFalse(page.contains("measurement files are not processed"));
            assertFalse(page.contains(">Successful measurements</th><td>0</td>"));
        }
    }

    @Test
    void packageSizeCountsEveryReportFile(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("reports").resolve("r1");
        Files.createDirectories(report.resolve("assets"));
        Files.writeString(report.resolve("report.html"), "<html></html>");
        Files.write(report.resolve("assets").resolve("map-route.png"), colored(Color.BLUE));
        Files.write(report.resolve("assets").resolve("evidence-001.png"), colored(Color.RED));
        long size = ReportPackageSize.bytes(report.resolve("report.html"));
        assertEquals(Files.size(report.resolve("report.html"))
                + Files.size(report.resolve("assets").resolve("map-route.png"))
                + Files.size(report.resolve("assets").resolve("evidence-001.png")), size);
        assertFalse(UiFormat.bytes(size).equals("0 KB"));
        assertTrue(UiFormat.bytes(500).endsWith(" B"));
    }

    @Test
    void reusedExportDirectoryDropsLegacyDetailMaps(@TempDir Path temp) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Clean").build());
            byte[] evidence = colored(Color.RED);
            service.writeSnapshotPng(saved.projectId(), saved.investigationId(), "shot", evidence);
            saved = service.save(store, saved.toBuilder().evidence(List.of(
                    shot(saved.investigationId(), "e1", "shot", 0, "Brake trace"))).build());
            Path out = temp.resolve("chosen");
            Path assets = out.resolve("assets");
            Files.createDirectories(assets);
            byte[] stale = new byte[] {9, 9, 9, 9};
            for (int i = 1; i <= 26; i++) {
                Files.write(assets.resolve(String.format("map-route-detail-%02d.png", i)), stale);
            }
            Path outside = temp.resolve("map-route-detail-01.png");
            Files.write(outside, stale);
            RouteMapReportContext context = new ProjectRouteReportLoader().load(
                    true, "", "All Measurements", 2, catalog(), new MemoryQuery(samples()), List.of(), List.of());
            RouteMapReportAsset map = RouteMapReportAsset.image(colored(Color.BLUE), context, List.of(
                    new RouteMapReportAsset.DetailImage("map-route-detail-01.png", stale)));
            List<SignalReportImage> signals = SignalReportRenderer.render(List.of(
                    new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{1, 2, 3})), null);
            Path html = out.resolve("Clean.html");
            service.writeHtmlReport(store, saved, "engineer", "Completed", html, null, map, signals);
            for (int i = 1; i <= 26; i++) {
                assertFalse(Files.exists(assets.resolve(String.format("map-route-detail-%02d.png", i))));
            }
            assertEquals(stale.length, Files.size(outside));
            assertTrue(Files.exists(assets.resolve("map-route.png")));
            assertTrue(Files.exists(assets.resolve("signals-waveform.png")));
            assertTrue(Files.exists(assets.resolve("evidence-001.png")));
            assertTrue(Files.size(assets.resolve("evidence-001.png")) > stale.length);
            long size = ReportPackageSize.bytes(html);
            long walked = Files.walk(out).filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception ex) {
                    return 0L;
                }
            }).sum();
            assertEquals(walked, size);
            assertFalse(UiFormat.bytes(size).equals("0 KB"));
            Path zip = temp.resolve("export.zip");
            service.exportPackage(store, saved, zip, "engineer", null, map, signals);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                assertTrue(zf.getEntry("report/assets/map-route.png") != null);
                assertTrue(zf.getEntry("report/assets/signals-waveform.png") != null);
                assertTrue(zf.getEntry("report/assets/evidence-001.png") != null);
                assertTrue(zf.getEntry("report/assets/map-route-detail-01.png") == null);
                for (int i = 1; i <= 26; i++) {
                    assertTrue(zf.getEntry(String.format("report/assets/map-route-detail-%02d.png", i)) == null);
                }
            }
        }
    }

    private void cancelAt(Path temp, String stage) throws Exception {
        install(temp.resolve("home"), temp.resolve("operator"));
        Path db = CentralSqlitePaths.ensureLayout();
        DesktopInvestigationService service = new DesktopInvestigationService();
        SimpleCancellationToken token = new SimpleCancellationToken();
        DesktopInvestigationService.reportCheckpoint = name -> {
            if (stage.equals(name)) {
                token.cancel();
            }
        };
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", Instant.now(), Instant.now()));
            EngineeringInvestigation saved = service.save(store,
                    EngineeringInvestigation.builder("p1").name("Cancel").build());
            List<SignalReportImage> signals = SignalReportRenderer.render(List.of(
                    new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{1, 3, 2})), null);
            assertThrows(AnalysisCancelledException.class, () -> service.generateHtmlReport(
                    store, saved, "engineer", token, null, signals));
            assertTrue(store.listInvestigationReports(saved.investigationId()).isEmpty());
            Path reports = InvestigationStoragePaths.root(
                    AnalysisStorageRoots.projectArtifactsRoot("p1"), saved.investigationId()).resolve("reports");
            if (Files.isDirectory(reports)) {
                try (var walk = Files.walk(reports)) {
                    assertTrue(walk.filter(Files::isRegularFile).findAny().isEmpty());
                }
            }
        }
    }

    private static String htmlWith(
            List<com.stellar.platform.analysis.investigation.report.SignalAnalysisSection> signals,
            EngineeringReportModel.RouteMapSection route,
            List<EngineeringReportModel.Figure> figures
    ) {
        EngineeringReportModel model = EngineeringReportModelBuilder.build(
                EngineeringInvestigation.builder("p1").name("Report").build(),
                new EngineeringReportModelBuilder.Context(
                        "StellarAI 1.0.1", "", null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), figures, List.of()));
        if (route != null) {
            model = model.withRouteMap(route);
        }
        model = model.withSignalAnalysis(signals);
        return EngineeringHtmlReportRenderer.render(model);
    }

    private static EngineeringEvidenceItem shot(
            String investigationId, String id, String ref, int order, String caption) {
        return new EngineeringEvidenceItem(id, investigationId, EvidenceKind.GRAPH_SNAPSHOT, ref, order,
                false, "", "", 0, caption, "", Instant.now());
    }

    private static byte[] colored(Color color) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
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

    private static List<SignalCatalogEntry> catalog() {
        return List.of(
                entry("Latitude", "src-a", "alpha.dat", 18.50, 18.55),
                entry("Longitude", "src-a", "alpha.dat", 73.80, 73.85));
    }

    private static SignalCatalogEntry entry(String name, String id, String file, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
        return new SignalCatalogEntry(key, "deg", 8, 0L, 7_000_000_000L, min, max, file, "");
    }

    private static Map<SignalCatalogKey, List<SignalSamplePoint>> samples() {
        return Map.of(
                key("Latitude", "src-a"), track(18.50, 0.005),
                key("Longitude", "src-a"), track(73.80, 0.005));
    }

    private static SignalCatalogKey key(String name, String id) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
    }

    private static List<SignalSamplePoint> track(double start, double step) {
        return java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> new SignalSamplePoint(i * 1_000_000_000L, start + i * step, null, null))
                .toList();
    }

    private static final class MemoryQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> samples;

        private MemoryQuery(Map<SignalCatalogKey, List<SignalSamplePoint>> samples) {
            this.samples = samples;
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
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
