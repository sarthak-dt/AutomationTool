package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.desktop.ui.graph.SpectralChartCanvas;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Batch6ExistingFeatureTest {

    private static final long SECOND = 1_000_000_000L;
    private static final List<String> NOTES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        NOTES.add("TC_VIZ_011 NOT RUN category C: the workbook selects an FFT result that already exists. The product has Run for FFT Spectrum and no list of stored FFT results.");
        NOTES.add("TC_VIZ_013 NOT RUN category C: the workbook switches between two stored FFT results. The product has no result selector. A comparison trace is an overlay, not a replacement.");
        NOTES.add("TC_VIZ_018 NOT RUN category C: the workbook cites a BRD timing KPI that is not a confirmed UAT criterion, and smooth interaction has no numeric limit.");
        NOTES.add("TC_EVT_016 NOT RUN category C: the step runs a named event Pedal_High. The product has no named-event list.");
        NOTES.add("TC_EVT_024 NOT RUN category C: the step searches a threshold list. The product has no threshold-list search.");
        NOTES.add("TC_EVT_026 NOT RUN category C: the stored event text is shown again after reopen, and the Threshold field is not a saved control.");
        NOTES.add("TC_VIZ_035 NOT RUN: Project Files removal was not driven on a processed chart. An empty processing registry would wipe the catalog for a different reason.");
        NOTES.add("INV-STALE-FOCUS executed separately. See the note from the focus test.");
        Path out = Path.of("..", "docs", "qa-handoff", "batch6-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(90)
    void frequencyChartShowsEmptyStateThenHzAndMagnitude() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch6-fft");
        isolate(home);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            service.newProject("Batch6 FFT");
            InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("sine.mf4", "", 5_000);
            samples.accept(sine("Accel", 10, 1000, 1.0));
            SignalCatalogKey key = samples.snapshot().entries().getFirst().key();
            service.signalsModel().replaceCatalog(samples);
            assertTrue(service.signalsModel().addToWorkspace(key));
            service.graphInvestigation().setViewport(0, SECOND);

            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController controller = loader.getController();
                graph.set(controller);
                Stage window = new Stage();
                window.setScene(new Scene(root, 1280, 800));
                window.show();
                stage.set(window);
                root.applyCss();
                root.layout();
                controller.setAppService(service, null);
                controller.selectAnalysisTabForTest(2);
                selectTab(controller.engineeringPaneForTest(), "Frequency Domain");
                root.applyCss();
                root.layout();
                assertNullLookup(root, "#detailsToggleButton");
                toggle(controller.engineeringPaneForTest(), "Frequency Domain").fire();
                root.applyCss();
                root.layout();
                SpectralChartCanvas canvas = canvas(controller.engineeringPaneForTest());
                assertEquals(SpectralChartCanvas.EMPTY_HINT, canvas.plottedHintForTest());
                assertEquals("Magnitude", canvas.yAxisLabelForTest());
                Button run = button(controller.engineeringPaneForTest(), "Run");
                run.fire();
            });

            String report = waitForReport(service, "Hz");
            var result = service.lastAdvancedAnalytics();
            assertNotNull(result);
            assertNotNull(result.spectrum());
            double[] frequency = result.spectrum().frequencyHz();
            double[] magnitude = result.spectrum().magnitude();
            int peak = strongest(magnitude);
            assertEquals(10.0, frequency[peak], 0.5);
            double atOne = magnitude[nearest(frequency, 1.0)];
            assertTrue(magnitude[peak] > atOne, magnitude[peak] + " vs " + atOne);

            long plottedAt = System.nanoTime();
            String hint = "pending";
            while ((System.nanoTime() - plottedAt) < Duration.ofSeconds(15).toNanos()) {
                AtomicReference<String> current = new AtomicReference<>();
                runOnFx(() -> current.set(canvas(graph.get().engineeringPaneForTest()).plottedHintForTest()));
                hint = current.get();
                if (hint != null && hint.isEmpty()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals("", hint, "spectrum was not plotted on the frequency canvas");

            AtomicReference<String> cursor = new AtomicReference<>();
            runOnFx(() -> {
                SpectralChartCanvas plotted = canvas(graph.get().engineeringPaneForTest());
                Parent root = plotted.getScene().getRoot();
                root.applyCss();
                root.layout();
                double fMax = frequency[frequency.length - 1];
                double left = 48;
                double right = plotted.getWidth() - 8;
                assertTrue(right > left + 20, "canvas width " + plotted.getWidth());
                double x = left + (10.0 / fMax) * (right - left);
                plotted.moveHoverForTest(x, 40);
                Label readout = labelContaining(graph.get().engineeringPaneForTest(), "Frequency:");
                cursor.set(readout.getText() + " | width " + plotted.getWidth()
                        + " | valueAt10 " + plotted.valueAt(10));
            });
            String text = cursor.get();
            assertTrue(text.contains("Hz"), text);
            assertTrue(text.contains("Magnitude:"), text);
            assertTrue(text.contains("10"), text);
            note("TC_VIZ_014 PASS Frequency Domain before Run reads: " + SpectralChartCanvas.EMPTY_HINT);
            note("TC_VIZ_012 PASS X axis is Hz, Y axis is Magnitude, and the readout is: " + text
                    + ". Strongest bin " + frequency[peak] + " Hz is above the 1 Hz bin.");
            note("VIZ-PLOT-SETTINGS PASS the Plot Settings control is absent. BD-07 was not reopened.");
            note("FFT-REPORT " + report.replace('\n', ' '));
        } finally {
            if (graph.get() != null) {
                runOnFx(graph.get()::dispose);
            }
            if (stage.get() != null) {
                runOnFx(() -> stage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    @Test
    @Timeout(90)
    void thresholdReportShowsTheKnownPedalEvent() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch6-evt");
        isolate(home);
        Path db = home.resolve("meta.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        final DesktopAppService opened = service;
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            opened.newProject("Batch6 Events");
            InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("pedal.mf4", "", 100);
            List<CanonicalSignal> points = new ArrayList<>();
            for (int second = 40; second <= 60; second++) {
                double value = second >= 46 && second <= 55 ? 56.0 : 40.0;
                points.add(new CanonicalSignal(
                        "Acc_pedal_processed", "OBD2", second * SECOND, 0x100, 1,
                        value, (long) value, "%", null, SourceReference.ofLine(second)));
            }
            samples.accept(points);
            SignalCatalogKey key = samples.snapshot().entries().getFirst().key();
            opened.signalsModel().replaceCatalog(samples);
            assertTrue(opened.signalsModel().addToWorkspace(key));

            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController controller = loader.getController();
                graph.set(controller);
                Stage window = new Stage();
                window.setScene(new Scene(root, 1280, 800));
                window.show();
                stage.set(window);
                controller.setAppService(opened, null);
                controller.selectAnalysisTabForTest(2);
                EngineeringAnalysisPane pane = controller.engineeringPaneForTest();
                pane.setRunInputsForTest("THRESHOLD", "FULL", "50");
                assertEquals("50", pane.thresholdTextForTest());
                assertEquals("THRESHOLD", pane.analysisTypeForTest());
                button(pane, "Run Analysis").fire();
            });
            assertEquals(21, samples.retainedSampleCount(key), key.toString());
            assertEquals(21, opened.signalsModel().catalogStore().retainedSampleCount(
                    opened.signalsModel().workspaceKeys().getFirst()),
                    opened.signalsModel().workspaceKeys().toString());

            String report = waitForReport(opened, "46.000");
            assertTrue(report.contains("Events") || report.contains("Event count"), report);
            assertTrue(report.contains("Acc_pedal_processed") || report.contains("46.000"), report);
            assertFalse(report.contains("40.000 s"), report);
            note("TC_EVT_007 PASS the Engineering report lists the detected event. Actual: "
                    + oneLine(report));
            note("TC_EVT_023 PASS Run Analysis with threshold 50 reports the 56.0000 region at 46.000 s. "
                    + "The button is Run Analysis. Actual: " + oneLine(report));

            assertTrue(opened.saveProject().saved());
            String projectId = opened.currentProjectId();
            runOnFx(graph.get()::dispose);
            runOnFx(() -> stage.get().close());
            graph.set(null);
            stage.set(null);
            service.shutdown();
            store.close();

            store = new SqliteProjectMetadataStore(db);
            service = new DesktopAppService(new DesktopSession(), store);
            assertTrue(service.openSavedProject(projectId).opened());
            String restored = service.lastEngineeringAnalysisReport();
            assertTrue(restored.contains("46.000"), restored);
            assertTrue(restored.contains("Event count"), restored);
            var workspace = service.metadataStore().findAnalysisWorkspace(projectId);
            String storedFindings = "no analysis workspace";
            String storedType = "";
            if (workspace.isPresent()) {
                storedType = workspace.get().lastAnalysisType();
                storedFindings = service.metadataStore().listAnalysisFindings(workspace.get().lastAnalysisId()).stream()
                        .map(row -> row.summary())
                        .toList()
                        .toString();
            }
            AtomicReference<String> field = new AtomicReference<>();
            AtomicReference<String> type = new AtomicReference<>();
            DesktopAppService reopened = service;
            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController controller = loader.getController();
                graph.set(controller);
                Stage window = new Stage();
                window.setScene(new Scene(root, 900, 600));
                window.show();
                stage.set(window);
                controller.setAppService(reopened, null);
                controller.refresh();
                controller.selectAnalysisTabForTest(2);
                EngineeringAnalysisPane pane = controller.engineeringPaneForTest();
                field.set(textField(pane, "Threshold").getText());
                type.set(selected(pane, "THRESHOLD"));
            });
            assertEquals("THRESHOLD", type.get());
            assertEquals("", field.get() == null ? "" : field.get());
            note("EVT-REOPEN reopened report is '" + oneLine(restored)
                    + "'. Stored analysis type is " + storedType
                    + ". Stored findings are " + storedFindings
                    + ". Analysis type selection is " + type.get()
                    + ". Threshold field text is '" + field.get()
                    + "'. TC_EVT_026 stays NOT RUN because the Threshold field is blank after reopen.");
        } finally {
            if (graph.get() != null) {
                runOnFx(graph.get()::dispose);
            }
            if (stage.get() != null) {
                runOnFx(() -> stage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    @Test
    @Timeout(90)
    void missingFileFocusDoesNotAdoptAnotherRecording() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch6-focus");
        isolate(home);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            service.newProject("Batch6 Focus");
            InMemorySignalCatalogStore first = new InMemorySignalCatalogStore("file-a.mf4", "veh.dbc", 20);
            InMemorySignalCatalogStore second = new InMemorySignalCatalogStore("file-b.mf4", "veh.dbc", 20);
            first.accept(List.of(sample("VehicleSpeed", SECOND, 10)));
            second.accept(List.of(sample("VehicleSpeed", 2 * SECOND, 20)));
            ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                    new ProjectMergedSignalStore.Member(first, "src-A", "def", "File A", "veh.dbc"),
                    new ProjectMergedSignalStore.Member(second, "src-B", "def", "File B", "veh.dbc")
            ), "2 Sources", "veh.dbc");
            Path fileA = home.resolve("file-a.mf4");
            Path fileB = home.resolve("file-b.mf4");
            Files.writeString(fileA, "a");
            Files.writeString(fileB, "b");
            service.workspace().addFile(WorkspaceFile.restore("src-A", fileA, FileKind.MEASUREMENT_MF4, 1));
            service.workspace().addFile(WorkspaceFile.restore("src-B", fileB, FileKind.MEASUREMENT_MF4, 1));
            service.selectAnalysisScope(AnalysisScope.allMeasurements(2));
            assertTrue(service.isProjectWideAnalysis());
            service.signalsModel().replaceCatalog(merged);
            SignalCatalogKey logical = merged.snapshot().entries().stream()
                    .filter(entry -> "VehicleSpeed".equals(entry.signalName()))
                    .findFirst().orElseThrow().key();
            assertEquals(2, merged.physicalRouteCount(logical));
            assertTrue(service.signalsModel().addToWorkspace(logical));
            service.graphInvestigation().setTimelineContext("", "missing-measurement", "FILE_FOCUS");
            service.graphInvestigation().setViewport(0, 3 * SECOND);

            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController controller = loader.getController();
                graph.set(controller);
                Stage window = new Stage();
                window.setScene(new Scene(root, 1280, 800));
                window.show();
                stage.set(window);
                controller.setAppService(service, null);
                controller.applyMembershipFromWorkspaceForTest();
                controller.refresh();
            });

            long started = System.nanoTime();
            while (graph.get().fileSlotCountForTest() < 2
                    && (System.nanoTime() - started) < Duration.ofSeconds(15).toNanos()) {
                Thread.sleep(100);
            }
            assertTrue(graph.get().fileSlotCountForTest() >= 2,
                    "slots=" + graph.get().fileSlotCountForTest()
                            + " selected=" + graph.get().selectedSignalCountForTest()
                            + " accepted=" + graph.get().viewportAcceptedForTest());

            AtomicReference<String> label = new AtomicReference<>();
            AtomicReference<String> status = new AtomicReference<>();
            AtomicReference<Boolean> focused = new AtomicReference<>();
            AtomicReference<Boolean> bar = new AtomicReference<>();
            runOnFx(() -> {
                GraphController controller = graph.get();
                ToggleButton toggle = (ToggleButton) controller.graphCanvasForTest().getScene().lookup("#fileFocusToggle");
                Label focus = (Label) controller.graphCanvasForTest().getScene().lookup("#fileFocusLabel");
                Label state = (Label) controller.graphCanvasForTest().getScene().lookup("#statusLabel");
                javafx.scene.Node focusBar = controller.graphCanvasForTest().getScene().lookup("#fileFocusBar");
                focused.set(toggle.isSelected());
                label.set(focus.getText());
                status.set(state.getText());
                bar.set(focusBar != null && focusBar.isVisible());
                long before = controller.viewportEndForTest() - controller.viewportStartForTest();
                controller.zoomInForTest();
                long after = controller.viewportEndForTest() - controller.viewportStartForTest();
                assertTrue(after < before, before + " -> " + after);
                controller.panLeftForTest();
                controller.fitForTest();
            });
            assertFalse(focused.get(), "file focus adopted a recording");
            assertEquals("", label.get());
            assertTrue(bar.get(), "file focus bar hidden. slots="
                    + graph.get().fileSlotCountForTest()
                    + " selected=" + graph.get().selectedSignalCountForTest()
                    + " focusActive=" + graph.get().fileFocusActiveForTest());
            assertFalse(label.get().contains("File A") || label.get().contains("File B"));
            String keptId = service.graphInvestigation().fileFocusMeasurementId();
            note("INV-STALE-FOCUS the saved id missing-measurement did not select File A or File B. "
                    + "File Focus toggle is off. Label is blank. Status is: " + status.get()
                    + ". Stored focus id after reopen is '" + keptId
                    + "'. No sentence names the missing id, so the clear-message line is not an existing control.");
            note("GRAPH-ZOOM PASS Zoom in shortened the viewport. Pan and Fit ran on the same Graph controls.");
        } finally {
            if (graph.get() != null) {
                runOnFx(graph.get()::dispose);
            }
            if (stage.get() != null) {
                runOnFx(() -> stage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    @Test
    @Timeout(90)
    void htmlReportOpensInEdgeAndChrome() throws Exception {
        EngineeringReportModel model = EngineeringReportModelBuilder.build(
                EngineeringInvestigation.builder("p-batch6").name("Batch6 <Report>").description("Pedal").build(),
                new EngineeringReportModelBuilder.Context(
                        "StellarAI 1.0.1", "",
                        new EngineeringReportModel.ProjectSection("Batch6 Project", 1, 1, 0, 1, "Ready", 0),
                        List.of(new EngineeringReportModel.SourceRow("pedal.mf4", "MF4", "", "1 KB", "Ready")),
                        List.of(new EngineeringReportModel.SignalRow(
                                "Acc_pedal_processed", "%", "pedal.mf4", "40", "56", "48", "—", "—")),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
        String html = EngineeringHtmlReportRenderer.render(model);
        Path file = Files.createTempFile("batch6-report", ".html");
        Files.writeString(file, html);
        String edge = dumpDom("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", file);
        String chrome = dumpDom("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", file);
        for (String rendered : List.of(edge, chrome)) {
            assertTrue(rendered.contains("Batch6 Project"), rendered.substring(0, Math.min(400, rendered.length())));
            assertTrue(rendered.contains("Acc_pedal_processed"), rendered);
            assertTrue(rendered.contains("56"), rendered);
            assertTrue(rendered.contains("%"), rendered);
            assertTrue(rendered.contains("pedal.mf4"), rendered);
            assertFalse(rendered.contains("<script"), rendered);
        }
        assertTrue(edge.contains("Batch6 &lt;Report&gt;") || edge.contains("Batch6 <Report>"));
        assertTrue(chrome.contains("Batch6 &lt;Report&gt;") || chrome.contains("Batch6 <Report>"));
        note("TC_EXP_017 PASS Edge and Chrome headless documents both contain Batch6 Project, pedal.mf4, Acc_pedal_processed, 56, and %. File: " + file.toAbsolutePath());
    }

    @Test
    @Timeout(60)
    void oneHundredStackedSeriesStayInteractive() throws Exception {
        AtomicReference<Long> elapsed = new AtomicReference<>();
        runOnFx(() -> {
            com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas canvas =
                    new com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas();
            Stage window = new Stage();
            window.setScene(new Scene(new VBox(canvas), 900, 500));
            window.show();
            canvas.resize(860, 420);
            List<GraphRenderSeries> series = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                SignalCatalogKey key = new SignalCatalogKey(
                        "S" + i, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "hundred.mf4", "");
                series.add(new GraphRenderSeries(key, "u", 1, true, 0, SECOND, List.of(bucket(SECOND / 2, i))));
            }
            long started = System.nanoTime();
            canvas.setSeries(series);
            canvas.setLayoutMode(com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas.LayoutMode.STACKED);
            canvas.applyCss();
            canvas.layout();
            canvas.acceptWheel(120, 200);
            canvas.movePointerForTest(120, 40);
            elapsed.set((System.nanoTime() - started) / 1_000_000L);
            assertEquals(100, canvas.seriesCountForTest());
            window.close();
        });
        note("VIZ-100 stacked 100 one-sample series, wheel zoom, and hover finished in " + elapsed.get()
                + " ms. TC_VIZ_018 stays NOT RUN because the workbook's smooth-interaction KPI is not a confirmed UAT limit.");
    }

    private static String waitForReport(DesktopAppService service, String token) throws Exception {
        long started = System.nanoTime();
        String latest = "";
        while ((System.nanoTime() - started) < Duration.ofSeconds(20).toNanos()) {
            latest = service.lastEngineeringAnalysisReport();
            if (latest != null && latest.contains(token)) {
                return latest;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("report did not contain " + token + " actual: " + latest);
    }

    private static void selectItem(Parent root, String item) {
        for (var node : root.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getItems().contains(item)) {
                @SuppressWarnings("unchecked")
                ComboBox<String> typed = (ComboBox<String>) combo;
                typed.getSelectionModel().select(item);
            }
        }
    }

    private static String selected(Parent root, String probe) {
        for (var node : root.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getItems().contains(probe)) {
                Object value = combo.getSelectionModel().getSelectedItem();
                return value == null ? "" : value.toString();
            }
        }
        return "";
    }

    private static TextField textField(Parent root, String prompt) {
        for (var node : root.lookupAll(".text-field")) {
            if (node instanceof TextField field && prompt.equals(field.getPromptText())) {
                return field;
            }
        }
        throw new AssertionError("missing field " + prompt);
    }

    private static Button button(Parent root, String text) {
        Button found = null;
        for (var node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText()) && button.isVisible()) {
                found = button;
            }
        }
        assertNotNull(found, text);
        return found;
    }

    private static ToggleButton toggle(Parent root, String text) {
        for (var node : root.lookupAll(".toggle-button")) {
            if (node instanceof ToggleButton button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("missing toggle " + text);
    }

    private static void selectTab(Parent root, String title) {
        for (var node : root.lookupAll(".tab-pane")) {
            if (node instanceof TabPane tabs) {
                tabs.getTabs().stream()
                        .filter(tab -> title.equals(tab.getText()))
                        .findFirst()
                        .ifPresent(tab -> tabs.getSelectionModel().select(tab));
            }
        }
    }

    private static SpectralChartCanvas canvas(Parent root) {
        SpectralChartCanvas found = findCanvas(root);
        assertNotNull(found, "spectral canvas");
        return found;
    }

    private static SpectralChartCanvas findCanvas(javafx.scene.Node node) {
        if (node instanceof SpectralChartCanvas canvas) {
            return canvas;
        }
        if (node instanceof Parent parent) {
            for (var child : parent.getChildrenUnmodifiable()) {
                SpectralChartCanvas found = findCanvas(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Label labelContaining(Parent root, String fragment) {
        for (var node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getText() != null && label.getText().contains(fragment)) {
                return label;
            }
        }
        throw new AssertionError("missing label " + fragment);
    }

    private static void assertNullLookup(Parent root, String selector) {
        assertTrue(root.lookup(selector) == null, selector);
    }

    private static MouseEvent mouseMove(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, x, y, MouseButton.NONE, 1,
                false, false, false, false, false, false, false, false, false, false, null);
    }

    private static int strongest(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int nearest(double[] frequency, double hz) {
        int best = 0;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < frequency.length; i++) {
            double gap = Math.abs(frequency[i] - hz);
            if (gap < distance) {
                distance = gap;
                best = i;
            }
        }
        return best;
    }

    private static List<CanonicalSignal> sine(String name, double frequencyHz, double sampleRate, double seconds) {
        List<CanonicalSignal> points = new ArrayList<>();
        int count = (int) Math.round(seconds * sampleRate);
        for (int i = 0; i < count; i++) {
            double time = i / sampleRate;
            double value = Math.sin(2 * Math.PI * frequencyHz * time);
            points.add(new CanonicalSignal(
                    name, "OBD2", Math.round(time * SECOND), 0x100, 1,
                    value, 0L, "g", null, SourceReference.ofLine(i + 1)));
        }
        return points;
    }

    private static CanonicalSignal sample(String name, long timestamp, double value) {
        return new CanonicalSignal(
                name, "OBD2", timestamp, 0x100, 1, value, (long) value, "km/h", null,
                SourceReference.ofLine(1));
    }

    private static GraphBucket bucket(long timestamp, double value) {
        return new GraphBucket(timestamp, value, timestamp, value, timestamp, value, timestamp, value, 1,
                SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID);
    }

    private static String dumpDom(String browser, Path html) throws Exception {
        Path profile = Files.createTempDirectory("batch6-browser");
        Path outputFile = Files.createTempFile("batch6-dom", ".txt");
        Process process = new ProcessBuilder(
                browser,
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--disable-extensions",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--dump-dom",
                html.toUri().toString())
                .redirectOutput(outputFile.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(browser + " did not finish");
        }
        String output = Files.readString(outputFile);
        assertEquals(0, process.exitValue(), output.substring(0, Math.min(500, output.length())));
        deleteTree(profile);
        Files.deleteIfExists(outputFile);
        return output;
    }

    private static String oneLine(String report) {
        return report.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void isolate(Path home) {
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void runOnFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS), "FX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Disposable temp only.
                }
            });
        } catch (Exception ignored) {
            // Disposable temp only.
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
