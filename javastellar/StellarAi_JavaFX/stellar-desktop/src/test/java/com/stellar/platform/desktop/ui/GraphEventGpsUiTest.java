package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.ReportEvidenceAssets;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graph event GPS link, map pin, and Engineering Report V2. */
class GraphEventGpsUiTest {

    private static final long POINT = 5_000_000_000L;
    private static final long WINDOW_END = 8_000_000_000L;
    private static final long SAMPLE = 5_020_000_000L;

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private InvestigationController investigation;
    private Stage stage;
    private Stage reportStage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose", () -> {
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
            if (reportStage != null) {
                reportStage.close();
                reportStage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(180)
    void graphEventLinksAStoredGnssSampleIntoTheMapAndReport() throws Exception {
        openGraph();
        SignalCatalogKey picture = key("BrakePressure", "MsgA");
        SignalCatalogKey eventSignal = key("VehicleSpeed", "MsgB");
        service.graphInvestigation().setViewport(0, 20_000_000_000L);
        service.signalsModel().addPhysicalToWorkspace(picture);
        JavaFxTestSupport.runOnFx("create events", () -> {
            assertTrue(stage.getScene().getRoot().lookupAll(".button").stream()
                    .anyMatch(node -> node instanceof Button button && "GPS".equals(button.getText())));
            assertTrue(graph.commitCreateInvestigationForTest("Graph GPS"));
            graph.pinEventSignalsForTest(List.of(eventSignal));
            graph.selectCursorAForTest(POINT);
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            fill(pointDialog, "Brake point", false);
            assertFalse(graph.commitAddEventForTest(pointDialog).isBlank());
            graph.selectCursorAForTest(POINT);
            graph.selectCursorBForTest(WINDOW_END);
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            fill(windowDialog, "Brake window", true);
            assertFalse(graph.commitAddEventForTest(windowDialog).isBlank());
        });
        var created = investigations().stream().filter(item -> "Graph GPS".equals(item.name())).findFirst().orElseThrow();
        service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
        JavaFxTestSupport.runOnFx("capture", () -> assertTrue(graph.commitCaptureSnapshotForTest("Brake view")));
        var shot = snapshot("Brake view");
        Path png = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(service.currentProjectId()),
                shot.investigationId(), shot.referenceId());
        String hash = ReportEvidenceAssets.sha256(png);
        EngineeringBookmarkMetadata point = bookmark("Brake point");
        JavaFxTestSupport.runOnFx("link the snapshot", () -> {
            Dialog<?> link = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(link, shot.referenceId());
            graph.commitLinkSnapshotForTest(link);
        });
        assertEquals(List.of(shot.referenceId()), EngineerEventDocuments.decode(
                service.findEngineeringBookmark(point.bookmarkId()).orElseThrow()).snapshotIds());
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        MapRoute route = new MapRoute(MapRouteBuilder.validateAndBuild(
                List.of(sample(4_000_000_000L, 19.030000), sample(SAMPLE, 19.030868), sample(WINDOW_END, 19.031200)),
                List.of(sample(4_000_000_000L, 74.726000), sample(SAMPLE, 74.726919), sample(WINDOW_END, 74.727200)),
                List.of()).model());
        assertEquals(3, route.model().size());
        JavaFxTestSupport.runOnFx("install the route", () ->
                graph.installGpsRouteForTest("meas-1", "route.mf4", route));

        Dialog<?>[] gps = new Dialog<?>[1];
        JavaFxTestSupport.runOnFx("request GPS", () -> {
            gps[0] = graph.gpsDialogForTest(point.bookmarkId());
            fire(gps[0], "Request GPS");
        });
        String detail = awaitDetail(gps[0], "Latitude");
        assertTrue(detail.contains("Point tolerance is") || dialogLabels(gps[0]).contains("Point tolerance is"), detail);
        assertTrue(detail.contains("0.020 s"), detail);
        assertTrue(detail.contains("not at the event timestamp"), detail);
        assertTrue(detail.contains("19.030868"), detail);
        assertTrue(detail.contains("nearest") || detail.contains("Nearest"), detail);
        long linkStarted = System.nanoTime();
        JavaFxTestSupport.runOnFx("link and show", () -> {
            assertFalse(graph.gpsLinkDisabledForTest(gps[0]));
            assertEquals("", graph.commitGpsLinkForTest(gps[0]));
            assertTrue(graph.showGpsOnMapForTest(gps[0]));
            graph.reloadEventsForTest();
        });
        long linkMs = (System.nanoTime() - linkStarted) / 1_000_000L;
        EngineeringBookmarkMetadata linked = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        GraphEventGps.Evidence saved = GraphEventGps.read(linked);
        assertEquals(GraphEventGps.MATCHED, saved.status());
        assertEquals(SAMPLE, saved.sampleTimestampNanos());
        assertEquals(19.030868, saved.latitude(), 1e-6);
        assertEquals(74.726919, saved.longitude(), 1e-6);
        assertEquals(POINT, linked.timestampNanos());
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Brake point") && row.contains("GPS sample matched")));
        assertTrue(graph.mapEventLabelForTest(0).contains("Brake point"));
        assertTrue(graph.mapEventLabelForTest(0).contains("nearest GNSS sample"));
        assertEquals(1, graph.mapSelectedIndexForTest());

        EngineeringBookmarkMetadata window = bookmark("Brake window");
        JavaFxTestSupport.runOnFx("link the window sample", () -> {
            Dialog<?> windowGps = graph.gpsDialogForTest(window.bookmarkId());
            graph.requestGpsForTest(windowGps);
            assertTrue(graph.gpsDetailForTest(windowGps).contains("first GNSS sample"));
            assertEquals("", graph.commitGpsLinkForTest(windowGps));
            graph.reloadEventsForTest();
        });
        GraphEventGps.Evidence windowGps = GraphEventGps.read(service.findEngineeringBookmark(window.bookmarkId()).orElseThrow());
        assertEquals(SAMPLE, windowGps.sampleTimestampNanos());
        assertEquals(GraphEventGps.KIND_WINDOW, windowGps.kind());
        assertTrue(windowGps.statement().contains("not the whole event"));
        String windowLabel = graph.mapEventLabelForTest(1);
        assertTrue(windowLabel.contains("Brake window"), windowLabel);
        assertTrue(windowLabel.contains("first GNSS sample in the window"), windowLabel);

        JavaFxTestSupport.runOnFx("edit keeps the sample", () -> {
            graph.selectEventIndexForTest(indexOf("Brake point"));
            assertTrue(graph.commitEventEditForTest(point.bookmarkId(), true, "Edited point", "Braking", "updated"));
        });
        EngineeringBookmarkMetadata edited = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(POINT, edited.timestampNanos());
        assertEquals(SAMPLE, GraphEventGps.read(edited).sampleTimestampNanos());
        assertEquals(List.of(shot.referenceId()), EngineerEventDocuments.decode(edited).snapshotIds());

        String projectId = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        JavaFxTestSupport.runOnFx("reopen", () -> {
            graph.setAppService(service, null);
            graph.installGpsRouteForTest("meas-1", "route.mf4", route);
        });
        EngineeringBookmarkMetadata reopened = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(reopened).status());
        assertEquals(SAMPLE, GraphEventGps.read(reopened).sampleTimestampNanos());
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("GPS sample matched")));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        openReport();
        JavaFxTestSupport.runOnFx("include both events", () -> {
            investigation.onShow();
            selectEngineerEvent("Edited point");
            button(reportStage, "Include in Report").fire();
            selectEngineerEvent("Brake window");
            button(reportStage, "Include in Report").fire();
        });
        Path html = export("gps-event", temp.resolve("out"), false);
        Path pdf = export("gps-event-pdf", temp.resolve("pdf"), true);
        String htmlText = Files.readString(html);
        String pdfText = pdfText(pdf).replace(" ", "");
        assertTrue(htmlText.contains("Edited point"));
        assertTrue(htmlText.contains("Brake window"));
        assertTrue(htmlText.contains("19.030868"));
        assertTrue(htmlText.contains("74.726919"));
        assertTrue(htmlText.contains("0.020 s"));
        assertTrue(htmlText.contains("Nearest stored GNSS sample within 1.000 s"));
        assertTrue(htmlText.contains("not the whole event"));
        assertTrue(htmlText.contains("not a picture of that event signal"));
        assertTrue(htmlText.contains("VehicleSpeed"));
        assertTrue(htmlText.contains("assets/"));
        assertFalse(htmlText.contains("http://"));
        assertEquals(1, count(htmlText, "<img"));
        assertTrue(pdfText.contains("19.030868"));
        assertTrue(pdfText.contains("74.726919"));
        assertTrue(pdfText.contains("0.020s"));
        assertTrue(pdfText.contains("notthewholeevent"));
        assertTrue(pdfText.contains("notapictureofthateventsignal"));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        long markerStarted = System.nanoTime();
        JavaFxTestSupport.runOnFx("refresh markers", () -> graph.reloadEventsForTest());
        long markerMs = (System.nanoTime() - markerStarted) / 1_000_000L;

        MapRoute other = new MapRoute(MapRouteBuilder.validateAndBuild(
                List.of(sample(1_000_000_000L, 10.0), sample(2_000_000_000L, 10.1)),
                List.of(sample(1_000_000_000L, 20.0), sample(2_000_000_000L, 20.1)),
                List.of()).model());
        String otherId = service.saveGraphEvent("", "Other road", "Braking", "", 1_000_000_000L, null,
                "file-b", "b.mf4", List.of("VehicleSpeed"), List.of(GraphInvestigationCodec.encodeKey(eventSignal)));
        JavaFxTestSupport.runOnFx("two routes", () -> {
            graph.installProjectRoutesForTest(new ProjectRoute(List.of(
                    new RouteSegment("meas-1", "route.mf4", route),
                    new RouteSegment("file-b", "b.mf4", other)), 2, 2));
            Dialog<?> otherGps = graph.gpsDialogForTest(otherId);
            graph.requestGpsForTest(otherGps);
            String text = graph.gpsDetailForTest(otherGps);
            assertTrue(text.contains("10.0") || text.contains("10.000"), text);
            assertFalse(text.contains("19.030868"), text);
            assertEquals("", graph.commitGpsLinkForTest(otherGps));
        });
        GraphEventGps.Evidence otherSaved = GraphEventGps.read(service.findEngineeringBookmark(otherId).orElseThrow());
        assertEquals(10.0, otherSaved.latitude(), 1e-6);
        assertEquals("file-b", otherSaved.sourceId());

        JavaFxTestSupport.runOnFx("remove the point link", () -> {
            Dialog<?> remove = graph.gpsDialogForTest(point.bookmarkId());
            assertEquals("", graph.commitGpsRemoveForTest(remove));
            graph.reloadEventsForTest();
        });
        EngineeringBookmarkMetadata removed = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(POINT, removed.timestampNanos());
        assertEquals(GraphEventGps.NOT_REQUESTED, GraphEventGps.read(removed).status());
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Edited point")));

        JavaFxTestSupport.runOnFx("stale window", () -> {
            graph.installGpsRouteForTest("meas-1", "route.mf4", MapRoute.emptyRoute());
            Dialog<?> stale = graph.gpsDialogForTest(window.bookmarkId());
            String text = graph.gpsDetailForTest(stale);
            assertTrue(text.contains("GPS evidence stale or missing"), text);
        });
        EngineeringBookmarkMetadata stale = service.findEngineeringBookmark(window.bookmarkId()).orElseThrow();
        assertEquals(POINT, stale.timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(stale));
        assertEquals(GraphEventGps.STALE, GraphEventGps.read(stale).status());
        assertTrue(GraphEventGps.read(stale).statement().contains("not treated as verified"));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        long used = usedHeap();
        System.out.println("M15 linkMs=" + linkMs + " markerMs=" + markerMs + " heapBytes=" + used);
        assertTrue(linkMs < 2_000L, "link took " + linkMs);
        assertTrue(markerMs < 2_000L, "marker refresh took " + markerMs);
    }

    private String awaitDetail(Dialog<?> dialog, String token) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        String text = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read GPS", () -> holder[0] = graph.gpsDetailForTest(dialog));
            text = holder[0] == null ? "" : holder[0];
            if (text.contains(token)) {
                return text;
            }
            Thread.sleep(40);
        }
        return text;
    }

    private void openGraph() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Event GPS");
        JavaFxTestSupport.runOnFx("load graph", () -> {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void openReport() throws Exception {
        JavaFxTestSupport.runOnFx("load report", () -> {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            investigation = loader.getController();
            reportStage = new Stage();
            reportStage.setScene(new Scene(root, 1280, 800));
            reportStage.show();
            investigation.setAppService(service, null);
            investigation.setOpenGeneratedReportForTest(false);
            investigation.onShow();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private Path export(String name, Path directory, boolean pdf) throws Exception {
        Files.createDirectories(directory);
        String extension = pdf ? ".pdf" : ".html";
        JavaFxTestSupport.runOnFx("export " + name, () -> {
            investigation.setReportDestinationForTest(name, directory);
            button(reportStage, pdf ? "Generate PDF Report" : "Generate HTML Report").fire();
        });
        long deadline = System.nanoTime() + 90_000_000_000L;
        Path written = null;
        while (System.nanoTime() < deadline) {
            try (var listing = Files.list(directory)) {
                for (Path path : listing.toList()) {
                    String file = path.getFileName().toString().toLowerCase();
                    if (file.endsWith(extension) && !file.endsWith(".writing") && Files.size(path) > 0) {
                        written = path;
                    }
                }
            }
            String[] status = new String[1];
            JavaFxTestSupport.runOnFx("status", () -> status[0] = investigation.reportStatusForTest());
            if (written != null && ("Report generated.".equals(status[0]) || status[0] == null || status[0].isBlank())) {
                break;
            }
            if (status[0] != null && (status[0].startsWith("Report could not") || "Report generation cancelled.".equals(status[0]))) {
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(written, "No " + extension + " was written.");
        return written;
    }

    @SuppressWarnings("unchecked")
    private void selectEngineerEvent(String title) {
        ListView<String> events = null;
        for (Node node : reportStage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list && !list.getItems().isEmpty()
                    && list.getItems().getFirst() instanceof String) {
                events = (ListView<String>) list;
            }
        }
        assertNotNull(events);
        for (String item : events.getItems()) {
            if (item.contains(title)) {
                events.getSelectionModel().select(item);
                return;
            }
        }
        throw new AssertionError("Missing event " + title);
    }

    private static void fire(Dialog<?> dialog, String text) {
        VBox box = (VBox) dialog.getDialogPane().getContent();
        for (Node node : box.getChildren()) {
            if (node instanceof HBox actions) {
                for (Node child : actions.getChildren()) {
                    if (child instanceof Button button && text.equals(button.getText())) {
                        button.fire();
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Missing " + text);
    }

    private static String dialogLabels(Dialog<?> dialog) {
        StringBuilder text = new StringBuilder();
        VBox box = (VBox) dialog.getDialogPane().getContent();
        for (Node node : box.getChildren()) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static Button button(Stage shown, String text) {
        for (Node node : shown.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button candidate && text.equals(candidate.getText())) {
                return candidate;
            }
        }
        throw new AssertionError("Missing button " + text);
    }

    private EngineeringBookmarkMetadata bookmark(String title) {
        return service.listEngineeringBookmarks().stream()
                .filter(item -> title.equals(InvestigationEventCodec.titleOf(item.observation())))
                .findFirst().orElseThrow();
    }

    private com.stellar.platform.analysis.investigation.EngineeringEvidenceItem snapshot(String caption) {
        return investigations().stream()
                .flatMap(item -> item.evidence().stream())
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT && caption.equals(item.caption()))
                .findFirst().orElseThrow();
    }

    private List<com.stellar.platform.analysis.investigation.EngineeringInvestigation> investigations() {
        return service.engineeringInvestigations().list(service.metadataStore(), service.currentProjectId());
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

    private static void fill(Dialog<?> dialog, String title, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText("note");
            } else if (node instanceof ComboBox<?> combo) {
                select(combo, "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : "Braking");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(ComboBox<?> combo, String value) {
        ((ComboBox<String>) combo).getSelectionModel().select(value);
    }

    private static SignalCatalogKey key(String name, String message) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, 100, 1, "file.mf4", "file.dbc");
    }

    private static SignalSamplePoint sample(long timestamp, double value) {
        return new SignalSamplePoint(timestamp, value, null, null);
    }

    private static String pdfText(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            from = text.indexOf(token, from);
            if (from < 0) {
                break;
            }
            found++;
            from += token.length();
        }
        return found;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
