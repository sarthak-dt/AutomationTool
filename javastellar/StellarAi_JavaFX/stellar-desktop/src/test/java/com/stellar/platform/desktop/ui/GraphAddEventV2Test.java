package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineerEventInclusion;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.SignalReportLoader;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Graph Add Event point and window, using the same dialog commit as the button. */
class GraphAddEventV2Test {

    private static final long POINT = 5_000_000_000L;
    private static final long WINDOW_END = 8_000_000_000L;
    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose graph", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
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
    @Timeout(90)
    void pointAndWindowStayOnTheSameBookmarkThroughTheReport() throws Exception {
        openGraph("Graph events");
        SignalCatalogKey speedA = key("Speed", "MsgA", 100);
        SignalCatalogKey speedB = key("Speed", "MsgB", 200);
        JavaFxTestSupport.runOnFx("save a point and a window", () -> {
            long addButtons = stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && "Add Event".equals(button.getText()))
                    .count();
            assertEquals(1, addButtons);
            assertTrue(stage.getScene().getRoot().lookupAll(".button").stream()
                    .anyMatch(node -> node instanceof Button button && "Zoom".equals(button.getText())));
            graph.pinEventSignalsForTest(List.of(speedA, speedB));
            graph.selectCursorAForTest(POINT);
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            assertTrue(dialogText(pointDialog).contains(POINT + " ns"));
            fill(pointDialog, "Brake point", "Braking", "left pad", false);
            String pointId = graph.commitAddEventForTest(pointDialog);
            assertFalse(pointId.isBlank());
            graph.pinEventSignalsForTest(List.of(speedA));
            graph.selectCursorAForTest(POINT);
            graph.selectCursorBForTest(WINDOW_END);
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            String shown = dialogText(windowDialog);
            assertTrue(shown.contains(POINT + " ns"));
            assertTrue(shown.contains(WINDOW_END + " ns"));
            fill(windowDialog, "Brake window", "Braking", "from A to B", true);
            String windowId = graph.commitAddEventForTest(windowDialog);
            assertFalse(windowId.isBlank());
        });
        var point = service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Brake point".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow();
        var window = service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Brake window".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow();
        assertEquals(POINT, point.timestampNanos());
        assertEquals(POINT, window.timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(window));
        assertEquals(List.of("Speed · MsgA", "Speed · MsgB"), EngineerEventDocuments.decode(point).signals());
        assertEquals(speedA, GraphInvestigationCodec.decodeKey(EngineerEventDocuments.signalKeys(point).get(0)));
        assertEquals(speedB, GraphInvestigationCodec.decodeKey(EngineerEventDocuments.signalKeys(point).get(1)));
        assertEquals(List.of("Speed"), EngineerEventDocuments.decode(window).signals());
        JavaFxTestSupport.runOnFx("focus change does not rewrite the saved signals", () -> {
            graph.pinEventSignalsForTest(List.of(key("Other", "MsgC", 300)));
            graph.reloadEventsForTest();
        });
        assertEquals(List.of("Speed · MsgA", "Speed · MsgB"),
                EngineerEventDocuments.decode(service.findEngineeringBookmark(point.bookmarkId()).orElseThrow()).signals());
        JavaFxTestSupport.runOnFx("edit keeps the timestamp and the window", () -> {
            graph.reloadEventsForTest();
            int index = indexOf("Brake window");
            graph.selectEventIndexForTest(index);
            assertTrue(graph.overlaySelectedForTest(window.bookmarkId()));
            assertTrue(graph.commitEventEditForTest(window.bookmarkId(), true, "Edited window", "Braking", "updated"));
            graph.zoomToSelectedEventForTest();
        });
        var edited = service.findEngineeringBookmark(window.bookmarkId()).orElseThrow();
        assertEquals(POINT, edited.timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(edited));
        assertEquals("Edited window", InvestigationEventCodec.titleOf(edited.observation()));
        assertTrue(graph.viewportStartForTest() <= POINT);
        assertTrue(graph.viewportEndForTest() >= WINDOW_END);
        JavaFxTestSupport.runOnFx("missing signals are marked and a delete removes one bookmark", () -> {
            graph.setEventCatalogForTest(List.of(key("Other", "MsgC", 300)));
            graph.reloadEventsForTest();
            assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Brake point") && row.contains("2 signals missing")));
            graph.setEventCatalogForTest(List.of(speedA, speedB));
            graph.reloadEventsForTest();
            assertTrue(graph.eventRowsForTest().stream().filter(row -> row.contains("Brake point"))
                    .noneMatch(row -> row.contains("missing")));
            service.persistInvestigationEvent("Legacy look", "Observation", "older", 1_000_000_000L, "EngineRPM");
            graph.reloadEventsForTest();
            assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Legacy look")));
            assertTrue(graph.commitDeleteForTest(point.bookmarkId()));
        });
        assertTrue(service.findEngineeringBookmark(point.bookmarkId()).isEmpty());
        assertTrue(service.findEngineeringBookmark(window.bookmarkId()).isPresent());
        assertTrue(service.findEngineeringBookmark(service.listEngineeringBookmarks().stream()
                .filter(bookmark -> "Legacy look".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow().bookmarkId()).isPresent());
        String projectId = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        var reopened = service.findEngineeringBookmark(window.bookmarkId()).orElseThrow();
        assertEquals(POINT, reopened.timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(reopened));
        JavaFxTestSupport.runOnFx("reopen shows the window", () -> {
            graph.setAppService(service, null);
            graph.reloadEventsForTest();
        });
        assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Edited window") && row.contains("to")));
        var created = service.createInvestigation("Graph report", "QA", "FULL");
        service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
        var included = service.includeEngineerEvent(window.bookmarkId());
        var again = service.includeEngineerEvent(window.bookmarkId());
        assertEquals(included.evidence().size(), again.evidence().size());
        assertTrue(EngineerEventInclusion.linked(again.evidence(), window.bookmarkId()));
        var spans = SignalReportLoader.includedEventSpans(service.listEngineeringBookmarks(), again.evidence());
        assertEquals(1, spans.size());
        assertEquals(POINT / 1_000_000_000d, spans.getFirst().startSeconds(), 0.0);
        assertEquals(WINDOW_END / 1_000_000_000d, spans.getFirst().endSeconds(), 0.0);
        Path html = service.generateInvestigationReport(
                service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                "Ada", temp.resolve("graph.html"));
        Path pdf = service.generateInvestigationPdfReport(
                service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                "Ada", temp.resolve("graph.pdf"));
        String text = Files.readString(html);
        assertEquals(1, count(text, window.bookmarkId()));
        assertTrue(text.contains("Edited window"));
        assertTrue(text.contains("updated"));
        assertTrue(text.contains(EngineeringFormats.seconds(POINT)));
        assertTrue(text.contains(EngineeringFormats.seconds(WINDOW_END)));
        assertFalse(text.contains("Brake point"));
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(packed.contains("Editedwindow"));
            assertTrue(packed.contains("5.000s"));
            assertTrue(packed.contains("8.000s"));
            assertFalse(packed.contains("Brakepoint"));
        }
    }

    @Test
    @Timeout(90)
    void fourHundredEventsReloadOnTheGraphThread() throws Exception {
        openGraph("Many events");
        for (int i = 0; i < 400; i++) {
            service.persistInvestigationEvent("Mark " + i, "Observation", "", i * 1_000_000_000L, "Speed");
        }
        service.saveGraphEvent("", "Overlap A", "Braking", "", 10_000_000_000L, 12_000_000_000L,
                "", "", List.of("Speed"), List.of());
        service.saveGraphEvent("", "Overlap B", "Braking", "", 11_000_000_000L, 13_000_000_000L,
                "", "", List.of("Speed"), List.of());
        long before = usedHeap();
        long[] elapsed = new long[1];
        JavaFxTestSupport.runOnFx("reload 402 events", () -> {
            long started = System.nanoTime();
            graph.reloadEventsForTest();
            graph.selectEventIndexForTest(10);
            graph.zoomToSelectedEventForTest();
            elapsed[0] = (System.nanoTime() - started) / 1_000_000L;
        });
        JavaFxTestSupport.drainFxPulse();
        long growth = usedHeap() - before;
        assertEquals(402, graph.eventRowsForTest().size());
        assertEquals(402, graph.overlayTimesForTest().size());
        assertTrue(elapsed[0] < 1_000L, "graph reload took " + elapsed[0] + " ms");
        assertTrue(growth < 64L * 1024L * 1024L, "heap grew " + growth + " bytes");
        System.out.println("M13 fxReloadMs=" + elapsed[0] + " heapGrowthBytes=" + growth
                + " events=" + graph.eventRowsForTest().size());
    }

    @Test
    @Timeout(180)
    void sierraPointUsesTheStoredSampleTimestamp() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = null;
            for (SignalCatalogEntry candidate : store.snapshot().entries()) {
                if ("CH01_FRONT_BUMPER_GRILL_AMBIENT".equals(candidate.signalName())) {
                    ch01 = candidate;
                }
            }
            assumeTrue(ch01 != null);
            var sample = store.querySamples(ch01.key(), 100, 1).getFirst();
            double kept = store.querySamples(ch01.key(), (int) (ch01.sampleCount() / 2), 1).getFirst().physicalValue();
            openGraph("Sierra event");
            SignalCatalogEntry signal = ch01;
            JavaFxTestSupport.runOnFx("save the Sierra sample time", () -> {
                graph.pinEventSignalsForTest(List.of(signal.key()));
                graph.selectCursorAForTest(sample.timestampNanos());
                Dialog<?> dialog = graph.addEventDialogForTest();
                assertTrue(dialogText(dialog).contains(sample.timestampNanos() + " ns"));
                fill(dialog, "Sierra point", "Thermal observation", "stored sample", false);
                assertFalse(graph.commitAddEventForTest(dialog).isBlank());
            });
            var saved = service.listEngineeringBookmarks().getFirst();
            assertEquals(sample.timestampNanos(), saved.timestampNanos());
            assertEquals(signal.key(), GraphInvestigationCodec.decodeKey(EngineerEventDocuments.signalKeys(saved).getFirst()));
            assertEquals(kept, store.querySamples(signal.key(), (int) (signal.sampleCount() / 2), 1).getFirst().physicalValue(), 0.0);
            System.out.println("M13 sierraNanos=" + saved.timestampNanos()
                    + " display=" + EngineeringFormats.seconds(saved.timestampNanos()));
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private void openGraph(String project) throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve(project.hashCode() + ".db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve(project.hashCode() + ".db")));
        service.newProject(project);
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

    private int indexOf(String title) {
        List<String> rows = graph.eventRowsForTest();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(title)) {
                return i;
            }
        }
        return -1;
    }

    private static void fill(Dialog<?> dialog, String title, String category, String note, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText(note);
            } else if (node instanceof ComboBox<?> combo) {
                select(combo, "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : category);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(ComboBox<?> combo, String value) {
        ((ComboBox<String>) combo).getSelectionModel().select(value);
    }

    private static String dialogText(Dialog<?> dialog) {
        StringBuilder text = new StringBuilder();
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof Label label) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static SignalCatalogKey key(String name, String message, int canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, 1, "file.mf4", "file.dbc");
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
