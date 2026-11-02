package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.ReportEvidenceAssets;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

/** Graph screen capture, link, and the report screen export. */
class GraphSnapshotLinkUiTest {

    private static final long VIEW_END = 20_000_000_000L;
    private static final long POINT = 5_000_000_000L;
    private static final long WINDOW_END = 8_000_000_000L;

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
    void graphSnapshotLinksTheSameImageIntoTheReport() throws Exception {
        openGraph();
        SignalCatalogKey workspace = key("Speed", "MsgA", "file.mf4");
        SignalCatalogKey eventSignal = key("Speed", "MsgB", "file.mf4");
        service.graphInvestigation().setViewport(0, VIEW_END);
        service.signalsModel().addPhysicalToWorkspace(workspace);
        JavaFxTestSupport.runOnFx("create the investigation and events", () -> {
            assertTrue(stage.getScene().getRoot().lookupAll(".button").stream()
                    .anyMatch(node -> node instanceof Button button && "Link Snapshot".equals(button.getText())));
            assertTrue(graph.commitCreateInvestigationForTest("Graph evidence"));
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
        var created = investigations().stream().filter(item -> "Graph evidence".equals(item.name())).findFirst().orElseThrow();
        service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
        EngineeringBookmarkMetadata point = bookmark("Brake point");
        EngineeringBookmarkMetadata window = bookmark("Brake window");
        List<String> eventKeys = EngineerEventDocuments.signalKeys(point);
        service.saveGraphEvent("", "Other file", "Braking", "", POINT, null, "src", "other.mf4",
                List.of("Speed"), List.of(GraphInvestigationCodec.encodeKey(key("Speed", "MsgA", "other.mf4"))));
        service.saveGraphEvent("", "Late point", "Braking", "", 50_000_000_000L, null, "", "",
                List.of("Speed"), List.of());
        service.persistInvestigationEvent("Legacy look", "Observation", "older", 1_000_000_000L, "EngineRPM");

        long captureStarted = System.nanoTime();
        JavaFxTestSupport.runOnFx("capture", () -> assertTrue(graph.commitCaptureSnapshotForTest("Brake view")));
        long captureMs = (System.nanoTime() - captureStarted) / 1_000_000L;
        var shot = snapshot("Brake view");
        Path png = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(service.currentProjectId()),
                shot.investigationId(), shot.referenceId());
        String hash = ReportEvidenceAssets.sha256(png);
        GraphSnapshotMetadata meta = GraphSnapshotMetadata.fromJson(
                Files.readString(png.resolveSibling(shot.referenceId() + ".json")));
        assertEquals(0L, meta.rangeStartNanos());
        assertEquals(VIEW_END, meta.rangeEndNanos());
        assertEquals(workspace, GraphInvestigationCodec.decodeKey(meta.signalKeys().getFirst()));
        assertFalse(meta.layout().isBlank());
        assertNotNull(hash);

        @SuppressWarnings("unchecked")
        Dialog<ButtonType>[] link = new Dialog[1];
        long[] linkMs = new long[1];
        JavaFxTestSupport.runOnFx("link the point and the window", () -> {
            Dialog<ButtonType> dialog = graph.linkSnapshotDialogForTest(point.bookmarkId());
            link[0] = dialog;
            graph.selectLinkSnapshotForTest(dialog, shot.referenceId());
            assertTrue(graph.linkPreviewReadyForTest(dialog), graph.linkDetailForTest(dialog));
            assertFalse(graph.linkDisabledForTest(dialog));
            long started = System.nanoTime();
            graph.commitLinkSnapshotForTest(dialog);
            linkMs[0] = (System.nanoTime() - started) / 1_000_000L;
            graph.selectLinkSnapshotForTest(dialog, shot.referenceId());
            graph.commitLinkSnapshotForTest(dialog);
        });
        EngineeringBookmarkMetadata linked = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(List.of(shot.referenceId()), EngineerEventDocuments.decode(linked).snapshotIds());
        assertEquals(POINT, linked.timestampNanos());
        assertEquals(eventKeys, EngineerEventDocuments.signalKeys(linked));
        assertFalse(linked.observation().contains(hash));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));
        JavaFxTestSupport.runOnFx("link the window from the same dialog", () -> {
            Dialog<ButtonType> dialog = graph.linkSnapshotDialogForTest(window.bookmarkId());
            graph.selectLinkSnapshotForTest(dialog, shot.referenceId());
            graph.commitLinkSnapshotForTest(dialog);
            Dialog<ButtonType> mismatch = graph.linkSnapshotDialogForTest(bookmark("Other file").bookmarkId());
            graph.selectLinkSnapshotForTest(mismatch, shot.referenceId());
            assertTrue(graph.linkDisabledForTest(mismatch));
            assertTrue(graph.linkDetailForTest(mismatch).contains("different measurement"));
            Dialog<ButtonType> late = graph.linkSnapshotDialogForTest(bookmark("Late point").bookmarkId());
            graph.selectLinkSnapshotForTest(late, shot.referenceId());
            assertTrue(graph.linkDisabledForTest(late));
            assertTrue(graph.linkDetailForTest(late).contains("does not cover the event"));
            graph.setRequiredNameTextForTest(graph.snapshotNameDialogForTest(), "Marker");
            assertTrue(graph.commitCaptureSnapshotForTest("Marker"));
        });
        assertEquals(List.of(), EngineerEventDocuments.decode(service.findEngineeringBookmark(
                bookmark("Other file").bookmarkId()).orElseThrow()).snapshotIds());
        assertEquals(List.of(), EngineerEventDocuments.decode(service.findEngineeringBookmark(
                bookmark("Late point").bookmarkId()).orElseThrow()).snapshotIds());
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        JavaFxTestSupport.runOnFx("edit keeps the snapshot", () -> {
            graph.reloadEventsForTest();
            graph.selectEventIndexForTest(indexOf("Brake point"));
            assertTrue(graph.commitEventEditForTest(point.bookmarkId(), true, "Edited point", "Braking", "updated"));
        });
        EngineeringBookmarkMetadata edited = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(POINT, edited.timestampNanos());
        assertEquals(List.of(shot.referenceId()), EngineerEventDocuments.decode(edited).snapshotIds());
        assertEquals(eventKeys, EngineerEventDocuments.signalKeys(edited));
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Brake view")));

        String projectId = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        JavaFxTestSupport.runOnFx("restore the graph", () -> graph.setAppService(service, null));
        EngineeringBookmarkMetadata reopened = service.findEngineeringBookmark(point.bookmarkId()).orElseThrow();
        assertEquals(List.of(shot.referenceId()), EngineerEventDocuments.decode(reopened).snapshotIds());
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        openReport();
        JavaFxTestSupport.runOnFx("reorder evidence and include events", () -> {
            investigation.onShow();
            List<String> order = investigation.evidenceReferenceOrderForTest();
            assertTrue(order.contains(shot.referenceId()));
            investigation.selectEvidenceForTest(order.getFirst());
            button(reportStage, "Move Down").fire();
            investigation.onShow();
            assertEquals(order.get(1), investigation.evidenceReferenceOrderForTest().getFirst());
            selectEngineerEvent("Edited point");
            button(reportStage, "Include in Report").fire();
            selectEngineerEvent("Brake window");
            button(reportStage, "Include in Report").fire();
            selectEngineerEvent("Legacy look");
            button(reportStage, "Include in Report").fire();
        });
        Path htmlDir = temp.resolve("html");
        Path pdfDir = temp.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        long reportStarted = System.nanoTime();
        Path html = export("Snapshot report", htmlDir, false);
        Path pdf = export("Snapshot report", pdfDir, true);
        long reportMs = (System.nanoTime() - reportStarted) / 1_000_000L;
        String text = Files.readString(html);
        assertTrue(text.contains("Edited point"));
        assertTrue(text.contains("Brake window"));
        assertTrue(text.contains("Brake view"));
        assertTrue(text.contains("Included once in Graph Evidence"));
        assertTrue(text.contains("No graph snapshot is linked."));
        assertEquals(2, count(text, "<img"));
        assertEquals(1, count(text, "evidence-001.png"));
        assertEquals(1, count(text, "evidence-002.png"));
        assertTrue(text.contains("assets/"));
        assertFalse(text.contains("http://"));
        assertFalse(text.contains("https://"));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(packed.contains("Editedpoint"));
            assertTrue(packed.contains("Brakewindow"));
            assertTrue(packed.contains("Brakeview"));
            assertTrue(packed.contains("IncludedonceinGraphEvidence"));
            assertTrue(imageCount(document) >= 1);
        }

        JavaFxTestSupport.runOnFx("exclude the snapshot", () -> {
            Dialog<ButtonType> dialog = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(dialog, shot.referenceId());
            graph.commitExcludeSnapshotForTest(dialog);
        });
        assertTrue(Files.isRegularFile(png));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));
        JavaFxTestSupport.runOnFx("reload the report after exclude", () -> investigation.onShow());
        Path excludedDir = temp.resolve("excluded");
        Files.createDirectories(excludedDir);
        Path excluded = export("Excluded report", excludedDir, false);
        String excludedText = Files.readString(excluded);
        assertTrue(excludedText.contains("excluded from this report"));
        assertTrue(excludedText.contains("Figure 1. Marker"));
        assertFalse(excludedText.contains("Figure 1. Brake view"));
        assertFalse(excludedText.contains("Figure 2. Brake view"));
        assertEquals(1, count(excludedText, "<img"));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));
        JavaFxTestSupport.runOnFx("include the same snapshot", () -> {
            Dialog<ButtonType> dialog = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(dialog, shot.referenceId());
            graph.commitIncludeSnapshotForTest(dialog);
        });
        JavaFxTestSupport.runOnFx("reload the report after include", () -> investigation.onShow());
        Path includedDir = temp.resolve("included");
        Files.createDirectories(includedDir);
        Path included = export("Included report", includedDir, false);
        String includedText = Files.readString(included);
        assertEquals(2, count(includedText, "<img"));
        assertTrue(includedText.contains("Brake view"));
        assertTrue(includedText.contains("Included once in Graph Evidence"));
        assertEquals(hash, ReportEvidenceAssets.sha256(png));

        String kept = service.deleteGraphSnapshot(shot.referenceId());
        assertTrue(kept.contains("still linked"));
        assertTrue(Files.isRegularFile(png));
        JavaFxTestSupport.runOnFx("unlink both events", () -> {
            Dialog<ButtonType> pointDialog = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(pointDialog, shot.referenceId());
            graph.commitUnlinkSnapshotForTest(pointDialog);
            Dialog<ButtonType> windowDialog = graph.linkSnapshotDialogForTest(window.bookmarkId());
            graph.selectLinkSnapshotForTest(windowDialog, shot.referenceId());
            graph.commitUnlinkSnapshotForTest(windowDialog);
        });
        assertEquals(List.of(), EngineerEventDocuments.decode(
                service.findEngineeringBookmark(point.bookmarkId()).orElseThrow()).snapshotIds());
        assertEquals("", service.deleteGraphSnapshot(shot.referenceId()));
        assertFalse(Files.isRegularFile(png));
        long used = usedHeap();
        System.out.println("M14 captureMs=" + captureMs + " linkMs=" + linkMs[0]
                + " reportMs=" + reportMs + " heapBytes=" + used);
        assertTrue(captureMs < 5_000L, "capture took " + captureMs + " ms");
        assertTrue(linkMs[0] < 1_000L, "link took " + linkMs[0] + " ms");
        assertTrue(reportMs < 20_000L, "report took " + reportMs + " ms");
        assertNotNull(link[0]);
    }

    private void openGraph() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Snapshot links");
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
            if (written != null && ("Report generated.".equals(status[0]) || status[0].isBlank())) {
                break;
            }
            if (status[0].startsWith("Report could not") || "Report generation cancelled.".equals(status[0])) {
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
        javafx.scene.layout.VBox body = (javafx.scene.layout.VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof javafx.scene.control.TextField field) {
                field.setText(title);
            } else if (node instanceof javafx.scene.control.TextArea area) {
                area.setText("note");
            } else if (node instanceof javafx.scene.control.ComboBox<?> combo) {
                select(combo, "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : "Braking");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(javafx.scene.control.ComboBox<?> combo, String value) {
        ((javafx.scene.control.ComboBox<String>) combo).getSelectionModel().select(value);
    }

    private static SignalCatalogKey key(String name, String message, String file) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, 100, 1, file, "file.dbc");
    }

    private static int imageCount(PDDocument document) {
        int images = 0;
        for (PDPage page : document.getPages()) {
            if (page.getResources() == null) {
                continue;
            }
            for (COSName name : page.getResources().getXObjectNames()) {
                if (page.getResources().isImageXObject(name)) {
                    images++;
                }
            }
        }
        return images;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
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
}
