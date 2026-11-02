package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GnssEventCorrelation;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.ReportEvidenceAssets;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One Sierra journey. Lines prefixed M20 STEP say whether the check used a JavaFX control
 * or a service call. File choosers are not clicked.
 */
class M20JourneySignoffTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");

    @TempDir Path temp;
    private DesktopAppService service;
    private Stage stage;
    private GraphController graph;
    private InvestigationController investigation;
    private ActiveSignalWorkspacePane signals;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose m20", () -> {
            if (signals != null) {
                signals = null;
            }
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (investigation != null) {
                investigation.dispose();
                investigation = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(600)
    void sierraEngineerJourneyThroughTheJavaFxControls() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        assertEquals("1.0.3", DesktopVersion.displayVersion());
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "m20-signoff");
        if (Files.exists(out)) {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(out);

        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        long decodeStart = System.nanoTime();
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        var processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        long decodeMs = (System.nanoTime() - decodeStart) / 1_000_000L;
        SignalSeriesStore store = processed.catalogStore();
        SignalCatalogEntry ch01 = entry(store, "CH01_FRONT_BUMPER_GRILL_AMBIENT");
        SignalCatalogEntry ch02 = entryPrefix(store, "CH02");
        SignalCatalogEntry latitude = entry(store, "LATITUDE");
        SignalCatalogEntry longitude = entry(store, "LONGITUDE");
        assumeTrue(ch01 != null && ch02 != null && latitude != null && longitude != null);
        SignalSamplePoint ch01First = store.querySamples(ch01.key(), 0, 1).getFirst();
        SignalSamplePoint ch01Later = store.querySamples(ch01.key(), 400, 1).getFirst();
        step("service", "decode Sierra CH01 firstNs=" + ch01First.timestampNanos()
                + " laterNs=" + ch01Later.timestampNanos() + " decodeMs=" + decodeMs);

        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("project.db")));
        service.newProject("M20 Sierra");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(DBC));
        service.signalsModel().replaceCatalog(store);
        service.signalsModel().setSamplePageSize(1_000);
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch01.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch02.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(latitude.key()));
        String projectId = service.currentProjectId();
        String sourceId = service.workspace().selectedMeasurement().orElseThrow().id();
        String sourceFile = service.workspace().selectedMeasurement().orElseThrow().fileName();
        step("service", "project created and files registered source=" + sourceFile);

        signals = showSignals();
        JavaFxTestSupport.runOnFx("inspect the aligned table", () -> {
            assertTrue(signals.toolbarStructureForTest().contains("navigateParent=toolbar"));
            assertEquals("Go", signals.indexGoButtonForTest().getText());
            assertFalse(signals.toolbarStructureForTest().contains("Go to Index"));
            assertTrue(signals.commandLabelsReadableForTest());
            signals.indexFieldForTest().setText("0");
            signals.indexGoButtonForTest().fire();
        });
        JavaFxTestSupport.drainFxPulse();
        Thread.sleep(400);
        JavaFxTestSupport.runOnFx("read the first aligned row", () -> {
            assertFalse(signals.table().getItems().isEmpty());
            long firstNs = signals.table().getItems().getFirst().timestampNanos();
            assertEquals(63_840_000L, firstNs);
            signals.selectNavigateTimeForTest();
            signals.indexFieldForTest().setText("0.08494");
            signals.indexGoButtonForTest().fire();
        });
        JavaFxTestSupport.drainFxPulse();
        shot(out.resolve("01-signal-workspace.png"));
        step("ui", "signal workspace index 0 is LATITUDE 63840000 ns; time jump fired; toolbar is Go");
        closeStage();

        openGraph();
        JavaFxTestSupport.runOnFx("add point and window from the graph dialog", () -> {
            assertTrue(stage.getScene().getRoot().lookupAll(".button").stream()
                    .anyMatch(node -> node instanceof Button button && "Add Event".equals(button.getText())));
            assertTrue(graph.commitCreateInvestigationForTest("M20 Sierra"));
            graph.pinEventSignalsForTest(List.of(ch01.key(), ch02.key()));
            graph.selectCursorAForTest(ch01First.timestampNanos());
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            assertTrue(dialogText(pointDialog).contains(ch01First.timestampNanos() + " ns"));
            fill(pointDialog, "Sierra point", "Thermal observation", "front bumper", false);
            assertFalse(graph.commitAddEventForTest(pointDialog).isBlank());
            graph.selectCursorAForTest(ch01First.timestampNanos());
            graph.selectCursorBForTest(ch01Later.timestampNanos());
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            assertTrue(dialogText(windowDialog).contains(ch01Later.timestampNanos() + " ns"));
            fill(windowDialog, "Sierra window", "Thermal observation", "A to B", true);
            assertFalse(graph.commitAddEventForTest(windowDialog).isBlank());
            graph.reloadEventsForTest();
            graph.selectEventIndexForTest(indexOf("Sierra window"));
            assertTrue(graph.commitEventEditForTest(
                    bookmark("Sierra window").bookmarkId(), true, "Sierra window", "Thermal observation", "edited window"));
            graph.zoomToSelectedEventForTest();
        });
        EngineeringBookmarkMetadata point = bookmark("Sierra point");
        EngineeringBookmarkMetadata window = bookmark("Sierra window");
        assertEquals(ch01First.timestampNanos(), point.timestampNanos());
        assertEquals(ch01Later.timestampNanos(), EngineerEventDocuments.endNanos(window));
        assertEquals(ch01.key(), GraphInvestigationCodec.decodeKey(EngineerEventDocuments.signalKeys(point).getFirst()));
        assertEquals(1, service.listEngineeringBookmarks().stream()
                .filter(item -> "Sierra point".equals(InvestigationEventCodec.titleOf(item.observation()))).count());
        shot(out.resolve("02-graph-events.png"));
        step("ui", "Add Event dialog saved point " + point.timestampNanos()
                + " ns and window end " + ch01Later.timestampNanos() + " ns; edit did not duplicate");

        JavaFxTestSupport.runOnFx("capture and link the snapshot", () -> {
            assertTrue(graph.commitCaptureSnapshotForTest("Sierra graph"));
            var shotItem = investigations().stream()
                    .flatMap(item -> item.evidence().stream())
                    .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT)
                    .findFirst().orElseThrow();
            Dialog<?> link = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(link, shotItem.referenceId());
            String linkDetail = graph.commitLinkSnapshotForTest(link);
            assertFalse(linkDetail.toLowerCase().contains("does not cover"), linkDetail);
            assertFalse(linkDetail.toLowerCase().contains("different measurement"), linkDetail);
        });
        var snapshotItem = investigations().stream()
                .flatMap(item -> item.evidence().stream())
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow();
        Path snapshotPng = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId),
                snapshotItem.investigationId(), snapshotItem.referenceId());
        String snapshotHash = ReportEvidenceAssets.sha256(snapshotPng);
        assertEquals(List.of(snapshotItem.referenceId()),
                EngineerEventDocuments.decode(service.findEngineeringBookmark(point.bookmarkId()).orElseThrow()).snapshotIds());
        step("ui", "snapshot " + snapshotItem.referenceId() + " hash=" + snapshotHash);

        List<SignalSamplePoint> lat = store.queryTimestampRange(
                latitude.key(), 0L, ch01Later.timestampNanos(), 400);
        List<SignalSamplePoint> lon = store.queryTimestampRange(
                longitude.key(), 0L, ch01Later.timestampNanos(), 400);
        MapRoute route = new MapRoute(MapRouteBuilder.validateAndBuild(lat, lon, List.of()).model());
        assertFalse(route.model().isEmpty());
        String eventSource = bookmark("Sierra point").sourceId();
        String eventFile = EngineerEventDocuments.decode(bookmark("Sierra point")).sourceFile();
        JavaFxTestSupport.runOnFx("install the decoded route for this measurement", () ->
                graph.installProjectRoutesForTest(new ProjectRoute(List.of(
                        new RouteSegment(eventSource, eventFile, route)), lat.size(), lon.size())));
        Dialog<?>[] gps = new Dialog<?>[1];
        JavaFxTestSupport.runOnFx("open GPS", () -> gps[0] = graph.gpsDialogForTest(point.bookmarkId()));
        JavaFxTestSupport.runOnFx("request the sample", () -> graph.requestGpsForTest(gps[0]));
        String detail = awaitGps(gps[0]);
        JavaFxTestSupport.runOnFx("link GPS and show the marker", () -> {
            assertEquals("", graph.commitGpsLinkForTest(gps[0]), graph.gpsDetailForTest(gps[0]));
            assertTrue(graph.showGpsOnMapForTest(gps[0]));
            graph.reloadEventsForTest();
        });
        GraphEventGps.Evidence gpsEvidence = GraphEventGps.read(service.findEngineeringBookmark(point.bookmarkId()).orElseThrow());
        assertEquals(GraphEventGps.MATCHED, gpsEvidence.status());
        long pointDelta = Math.abs(gpsEvidence.sampleTimestampNanos() - ch01First.timestampNanos());
        assertTrue(pointDelta <= GnssEventCorrelation.POINT_TOLERANCE_NANOS, Long.toString(pointDelta));
        assertTrue(pointDelta > 0L, "the linked sample is a stored GNSS time, not the event time");
        assertTrue(sampleOnRoute(route, gpsEvidence),
                gpsEvidence.sampleTimestampNanos() + " route0=" + route.model().timestampNanos(0)
                        + " " + gpsEvidence.statement());
        assertTrue(gpsEvidence.statement().contains("not interpolated"), gpsEvidence.statement());
        shot(out.resolve("03-gps-map.png"));
        step("ui", "GPS matched sampleNs=" + gpsEvidence.sampleTimestampNanos()
                + " lat=" + gpsEvidence.latitude() + " lon=" + gpsEvidence.longitude()
                + " routeSamples=" + route.model().size() + " detail=" + detail.replace('\n', ' '));

        Dialog<?>[] windowDialog = new Dialog<?>[1];
        JavaFxTestSupport.runOnFx("request the window sample", () -> {
            windowDialog[0] = graph.gpsDialogForTest(window.bookmarkId());
            graph.requestGpsForTest(windowDialog[0]);
        });
        String windowDetail = awaitGps(windowDialog[0]);
        JavaFxTestSupport.runOnFx("link the window sample", () ->
                assertEquals("", graph.commitGpsLinkForTest(windowDialog[0]), windowDetail));
        GraphEventGps.Evidence windowGps = GraphEventGps.read(service.findEngineeringBookmark(window.bookmarkId()).orElseThrow());
        assertEquals(GraphEventGps.KIND_WINDOW, windowGps.kind());
        assertTrue(windowGps.sampleTimestampNanos() >= ch01First.timestampNanos());
        assertTrue(windowGps.sampleTimestampNanos() <= ch01Later.timestampNanos());
        assertTrue(sampleOnRoute(route, windowGps), windowGps.statement());
        closeStage();

        EngineeringInvestigation saved = service.saveInvestigation(investigations().stream()
                .filter(item -> "M20 Sierra".equals(item.name())).findFirst().orElseThrow()
                .toBuilder()
                .signalKeyBlobs(List.of(
                        GraphInvestigationCodec.encodeKey(ch01.key()),
                        GraphInvestigationCodec.encodeKey(ch02.key()),
                        GraphInvestigationCodec.encodeKey(latitude.key())))
                .build());
        step("service", "report signal keys saved because the picker reads the SQLite catalog");

        openInvestigation();
        long openStart = System.nanoTime();
        JavaFxTestSupport.runOnFx("select engineering template and add an observation", () -> {
            ComboBox<?> templates = (ComboBox<?>) stage.getScene().getRoot().lookup("#templateBox");
            @SuppressWarnings("unchecked")
            ComboBox<EngineeringReportTemplate> box = (ComboBox<EngineeringReportTemplate>) templates;
            box.getSelectionModel().select(EngineeringReportTemplate.ENGINEERING_V2);
            TextField title = (TextField) stage.getScene().getRoot().lookup("#observationTitle");
            TextArea note = (TextArea) stage.getScene().getRoot().lookup("#observationNote");
            title.setText("Ambient note");
            note.setText("User-authored. The front bumper temperature was inspected. Not a diagnosis.");
            button("Add Observation").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        long openMs = (System.nanoTime() - openStart) / 1_000_000L;
        JavaFxTestSupport.runOnFx("include both events in the report", () -> {
            chooseEngineeringTemplate();
            selectEngineerEvent("Sierra point");
            button("Include in Report").fire();
            selectEngineerEvent("Sierra window");
            button("Include in Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        shot(out.resolve("04-report-builder.png"));
        Path htmlDir = out.resolve("html");
        Path pdfDir = out.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        export("m20-sierra", htmlDir, false);
        export("m20-sierra", pdfDir, true);
        JavaFxTestSupport.runOnFx("preview the report", () -> button("Preview HTML").fire());
        awaitStatus("Preview opened.", 180);
        JavaFxTestSupport.runOnFx("exclude then include the point event", () -> {
            selectEngineerEvent("Sierra point");
            button("Remove from Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("include the point event again", () -> {
            selectEngineerEvent("Sierra point");
            button("Include in Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        step("ui", "report builder open+observation ms=" + openMs
                + " preview/html/pdf buttons used; exclude and include fired");

        Path html = Files.list(htmlDir).filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        Path pdf = Files.list(pdfDir).filter(path -> path.getFileName().toString().endsWith(".pdf")).findFirst().orElseThrow();
        String htmlText = Files.readString(html);
        String pdfText;
        int pages;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pages = document.getNumberOfPages();
            pdfText = new PDFTextStripper().getText(document);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 110);
                ImageIO.write(image, "png", out.resolve("pdf-page-" + (page + 1) + ".png").toFile());
            }
        }
        assertTrue(htmlText.contains("Sierra point"), htmlText);
        assertTrue(htmlText.contains("Sierra window"));
        assertTrue(htmlText.contains("Ambient note"));
        assertTrue(htmlText.contains("observed=45"));
        assertTrue(htmlText.contains(Long.toString(ch01First.timestampNanos())));
        assertTrue(pdfText.contains("Sierra point"));
        assertTrue(pdfText.contains("observed=45"));
        assertFalse(htmlText.contains("1000 times"));
        String exportedHash = ReportEvidenceAssets.sha256(snapshotPng);
        assertEquals(snapshotHash, exportedHash);
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        step("ui", "htmlBytes=" + Files.size(html) + " pdfBytes=" + Files.size(pdf)
                + " pages=" + pages + " snapshotHashUnchanged=true usedBytes=" + heap);

        var reopened = service.openSavedProject(projectId);
        assertTrue(reopened.opened(), reopened.warnings().toString());
        assertEquals(ch01First.timestampNanos(), bookmark("Sierra point").timestampNanos());
        assertEquals(snapshotHash, ReportEvidenceAssets.sha256(snapshotPng));
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(bookmark("Sierra point")).status());
        step("service", "reopen kept the point timestamp, snapshot hash, and GPS match");

        GraphEventGps.Evidence unavailable = GraphEventGps.review(bookmark("Sierra point"),
                new GraphEventGps.RouteView(com.stellar.platform.processing.map.MapRouteModel.empty(),
                        sourceId, sourceFile, "", "LATITUDE", "LONGITUDE"));
        assertFalse(GraphEventGps.MATCHED.equals(unavailable.status()));
        step("service", "empty route reports GPS " + unavailable.status() + " and does not invent a coordinate");
        System.out.println("M20 ARTIFACTS " + out.toAbsolutePath());
    }

    private static boolean sampleOnRoute(MapRoute route, GraphEventGps.Evidence evidence) {
        for (int i = 0; i < route.model().size(); i++) {
            if (route.model().timestampNanos(i) == evidence.sampleTimestampNanos()) {
                return Math.abs(route.model().latitude(i) - evidence.latitude()) <= 1e-5d
                        && Math.abs(route.model().longitude(i) - evidence.longitude()) <= 1e-5d;
            }
        }
        return false;
    }

    private String awaitGps(Dialog<?> dialog) throws Exception {
        String before = graph.gpsDetailForTest(dialog);
        long deadline = System.nanoTime() + 8_000_000_000L;
        String detail = before;
        while (System.nanoTime() < deadline) {
            detail = graph.gpsDetailForTest(dialog);
            if (!detail.isBlank() && !detail.equals(before)) {
                return detail;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(50);
        }
        return detail;
    }

    private void step(String kind, String detail) {
        System.out.println("M20 STEP kind=" + kind + " " + detail);
    }

    private ActiveSignalWorkspacePane showSignals() throws Exception {
        ActiveSignalWorkspacePane[] pane = new ActiveSignalWorkspacePane[1];
        JavaFxTestSupport.runOnFx("show signal workspace", () -> {
            pane[0] = new ActiveSignalWorkspacePane();
            stage = new Stage();
            stage.setScene(new Scene(pane[0], 1280, 800));
            stage.show();
            pane[0].setModel(service.signalsModel());
        });
        JavaFxTestSupport.drainFxPulse();
        Thread.sleep(800);
        return pane[0];
    }

    private void openGraph() throws Exception {
        JavaFxTestSupport.runOnFx("show graph", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void openInvestigation() throws Exception {
        JavaFxTestSupport.runOnFx("show report builder", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            investigation = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            investigation.setAppService(service, null);
            investigation.setOpenGeneratedReportForTest(false);
            investigation.refresh();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void closeStage() throws Exception {
        JavaFxTestSupport.runOnFx("close stage", () -> {
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void export(String name, Path directory, boolean pdf) throws Exception {
        JavaFxTestSupport.runOnFx("export " + name, () -> {
            chooseEngineeringTemplate();
            investigation.setReportDestinationForTest(name, directory);
            button(pdf ? "Generate PDF Report" : "Generate HTML Report").fire();
        });
        String extension = pdf ? ".pdf" : ".html";
        long deadline = System.nanoTime() + 180_000_000_000L;
        Path written = null;
        String status = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read report status", () -> holder[0] = investigation.reportStatusForTest());
            status = holder[0] == null ? "" : holder[0];
            try (var listing = Files.list(directory)) {
                for (Path path : listing.toList()) {
                    String filename = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (filename.endsWith(extension) && !filename.endsWith(".writing") && Files.size(path) > 0) {
                        written = path;
                    }
                }
            }
            if (written != null) {
                waitUntilEnabled(pdf ? "Generate PDF Report" : "Generate HTML Report");
                System.out.println("M20 EXPORT " + extension + " status=" + status + " bytes=" + Files.size(written));
                return;
            }
            if (status.startsWith("Report could not") || "Report generation cancelled.".equals(status)) {
                break;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No " + extension + " was written in " + directory + " status=" + status);
    }

    private void waitUntilEnabled(String label) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] enabled = new boolean[1];
            JavaFxTestSupport.runOnFx("button " + label, () -> enabled[0] = !button(label).isDisabled());
            if (enabled[0]) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private void awaitStatus(String expected, int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        String status = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read report status", () -> holder[0] = investigation.reportStatusForTest());
            status = holder[0] == null ? "" : holder[0];
            if (status.contains(expected) || status.contains("could not")) {
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(status.contains(expected), status);
    }

    private void shot(Path file) throws Exception {
        JavaFxTestSupport.runOnFx("screenshot", () -> {
            WritableImage image = stage.getScene().snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        });
    }

    private List<EngineeringInvestigation> investigations() {
        return service.engineeringInvestigations().list(service.metadataStore(), service.currentProjectId());
    }

    private EngineeringBookmarkMetadata bookmark(String title) {
        return service.listEngineeringBookmarks().stream()
                .filter(item -> title.equals(InvestigationEventCodec.titleOf(item.observation())))
                .findFirst().orElseThrow();
    }

    private int indexOf(String title) {
        List<String> rows = graph.eventRowsForTest();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(title)) {
                return i;
            }
        }
        return -1;
    }

    private void chooseEngineeringTemplate() {
        @SuppressWarnings("unchecked")
        ComboBox<EngineeringReportTemplate> box =
                (ComboBox<EngineeringReportTemplate>) stage.getScene().getRoot().lookup("#templateBox");
        box.getSelectionModel().select(EngineeringReportTemplate.ENGINEERING_V2);
        stage.getScene().getRoot().lookupAll(".check-box").forEach(node -> {
            if (node instanceof javafx.scene.control.CheckBox check) {
                check.setSelected(true);
            }
        });
    }

    private void selectEngineerEvent(String title) {
        ListView<?> events = (ListView<?>) stage.getScene().getRoot().lookup("#engineerEventList");
        for (int i = 0; i < events.getItems().size(); i++) {
            if (String.valueOf(events.getItems().get(i)).contains(title)) {
                events.getSelectionModel().select(i);
            }
        }
    }

    private Button button(String text) {
        return stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof Button candidate && text.equals(candidate.getText()))
                .map(Button.class::cast)
                .findFirst().orElseThrow();
    }

    private static void fill(Dialog<?> dialog, String title, String category, String note, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText(note);
            } else if (node instanceof ComboBox<?> combo) {
                @SuppressWarnings("unchecked")
                ComboBox<String> choices = (ComboBox<String>) combo;
                String value = "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : category;
                choices.getSelectionModel().select(value);
            }
        }
    }

    private static String dialogText(Dialog<?> dialog) {
        StringBuilder text = new StringBuilder();
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static SignalCatalogEntry entryPrefix(SignalSeriesStore store, String prefix) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (candidate.signalName() != null && candidate.signalName().startsWith(prefix)) {
                return candidate;
            }
        }
        return null;
    }
}
