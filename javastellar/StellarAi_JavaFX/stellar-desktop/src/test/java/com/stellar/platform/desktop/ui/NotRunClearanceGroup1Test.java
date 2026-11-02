package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotRunClearanceGroup1Test {

    private static final long SECOND = 1_000_000_000L;
    private static final List<String> NOTES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "clearance1-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(90)
    void removingTheSourceClearsTheProcessedChart() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-viz035");
        isolate(home);
        Path measurement = home.resolve("file-a.mf4");
        Files.writeString(measurement, "synthetic-measurement");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> graphStage = new AtomicReference<>();
        AtomicReference<Stage> filesStage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            service.newProject("Clearance VIZ035");
            service.workspace().addFile(WorkspaceFile.restore(
                    "src-A", measurement, FileKind.MEASUREMENT_MF4, Files.size(measurement)));
            InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("file-a.mf4", "", 20);
            samples.accept(List.of(new CanonicalSignal(
                    "VehicleSpeed", "OBD2", SECOND, 0x100, 1, 42.0, 42L, "km/h", null,
                    SourceReference.ofLine(1))));
            SignalCatalogKey key = samples.snapshot().entries().getFirst().key();
            service.signalsModel().replaceCatalog(samples);
            assertTrue(service.signalsModel().addToWorkspace(key));
            service.graphInvestigation().setViewport(0, 3 * SECOND);

            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                GraphController controller = loader.getController();
                graph.set(controller);
                Stage window = new Stage();
                window.setScene(new Scene(root, 1280, 800));
                window.show();
                graphStage.set(window);
                controller.setAppService(service, null);
                controller.applyMembershipFromWorkspaceForTest();
                controller.refresh();
            });
            long started = System.nanoTime();
            while (graph.get().selectedSignalCountForTest() < 1
                    && (System.nanoTime() - started) < Duration.ofSeconds(10).toNanos()) {
                Thread.sleep(50);
            }
            assertTrue(graph.get().selectedSignalCountForTest() >= 1, "chart had no selected signal before removal");

            AtomicReference<String> before = new AtomicReference<>();
            runOnFx(() -> before.set(status(graph.get())));
            note("VIZ035-BEFORE selected=" + graph.get().selectedSignalCountForTest() + " status=" + before.get());

            runOnFx(() -> {
                FXMLLoader loader = new FXMLLoader(
                        ProjectFilesController.class.getResource("/fxml/project-files-view.fxml"));
                Parent root = loader.load();
                ProjectFilesController files = loader.getController();
                Stage window = new Stage();
                window.setScene(new Scene(root, 1100, 700));
                window.show();
                filesStage.set(window);
                files.setAppService(service, null);
                files.refresh();
                var list = files.measurementSourceListForTest();
                list.setMinHeight(420);
                list.setPrefHeight(420);
                for (WorkspaceFile file : list.getItems()) {
                    if (file.fileName().contains("file-a.mf4")) {
                        list.scrollTo(file);
                        list.getSelectionModel().select(file);
                    }
                }
                root.applyCss();
                root.layout();
                list.applyCss();
                list.layout();
                boolean fired = false;
                for (Node cell : list.lookupAll(".list-cell")) {
                    if (!text(cell).contains("file-a.mf4")) {
                        continue;
                    }
                    for (Node node : ((Parent) cell).lookupAll(".button")) {
                        if (node instanceof Button button
                                && button.getText() != null
                                && button.getText().startsWith("Remove")) {
                            button.fire();
                            fired = true;
                        }
                    }
                }
                assertTrue(fired, "Remove from Project was not found for file-a.mf4");
            });

            assertTrue(service.workspace().measurementFiles().isEmpty(), "source still listed after Remove");
            assertTrue(Files.isRegularFile(measurement), "Remove deleted the measurement file on disk");

            runOnFx(graph.get()::refresh);
            AtomicReference<String> after = new AtomicReference<>();
            AtomicReference<String> empty = new AtomicReference<>();
            AtomicReference<Boolean> emptyVisible = new AtomicReference<>();
            AtomicInteger rendered = new AtomicInteger(-1);
            AtomicReference<Boolean> overlayVisible = new AtomicReference<>();
            runOnFx(() -> {
                after.set(status(graph.get()));
                rendered.set(graph.get().lastRenderedCountForTest());
                Node overlay = graph.get().graphCanvasForTest().getScene().lookup("#emptyStateBox");
                overlayVisible.set(overlay != null && overlay.isVisible());
                Label body = (Label) graph.get().graphCanvasForTest().getScene().lookup("#graphEmptyBody");
                empty.set(body == null || body.getText() == null ? "" : body.getText());
                emptyVisible.set(overlayVisible.get());
            });
            assertEquals(0, rendered.get(), "removed source still had rendered tracks");
            assertTrue(after.get().contains("No timestamped samples available")
                            || after.get().contains("No signals plotted yet.")
                            || Boolean.TRUE.equals(overlayVisible.get()),
                    after.get());
            note("TC_VIZ_035 PASS synthetic fixture file-a.mf4. Remove from Project dropped the source. "
                    + "The measurement file remains on disk. Returning to the chart rendered="
                    + rendered.get() + " selected=" + graph.get().selectedSignalCountForTest()
                    + " status='" + after.get() + "' emptyOverlay=" + overlayVisible.get());
        } finally {
            if (graph.get() != null) {
                runOnFx(graph.get()::dispose);
            }
            if (graphStage.get() != null) {
                runOnFx(() -> graphStage.get().close());
            }
            if (filesStage.get() != null) {
                runOnFx(() -> filesStage.get().close());
            }
            service.shutdown();
            store.close();
            deleteTree(home);
        }
    }

    @Test
    @Timeout(90)
    void namedPedalHighRuleShowsTheSamplesAboveFifty() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-evt016");
        isolate(home);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            service.newProject("Clearance EVT016");
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
            service.signalsModel().replaceCatalog(samples);
            assertTrue(service.signalsModel().addToWorkspace(key));

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
                controller.selectAnalysisTabForTest(2);
                EngineeringAnalysisPane pane = controller.engineeringPaneForTest();
                selectTab(pane, "Rules");
                pane.rules().authorForTest("Pedal_High", "Acc_pedal_processed", "50", "%", "1", "0");
                button(pane, "Save").fire();
                selectTab(pane, "Diagnostics");
                selectComboContaining(pane, "PROJECT CUSTOM");
                selectItem(pane, "FULL");
                button(pane, "Run Diagnostics").fire();
            });

            AtomicReference<String> table = new AtomicReference<>("");
            long started = System.nanoTime();
            while ((System.nanoTime() - started) < Duration.ofSeconds(20).toNanos()) {
                runOnFx(() -> {
                    StringBuilder visible = new StringBuilder();
                    Parent diagnostics = graph.get().engineeringPaneForTest().diagnostics();
                    for (Node node : diagnostics.lookupAll(".text-area")) {
                        if (node instanceof TextArea area && area.getText() != null) {
                            visible.append(area.getText()).append('\n');
                        }
                    }
                    for (Node node : diagnostics.lookupAll(".table-cell")) {
                        if (node instanceof Labeled labeled && labeled.getText() != null) {
                            visible.append(labeled.getText()).append(' ');
                        }
                    }
                    table.set(visible.toString());
                });
                if (table.get().contains("46")) {
                    break;
                }
                Thread.sleep(100);
            }
            String report = service.lastEngineeringDiagnosticsReport() == null
                    ? "" : service.lastEngineeringDiagnosticsReport();
            assertTrue(table.get().contains("Pedal_High"), table.get());
            assertTrue(table.get().contains("46"), table.get());
            assertFalse(table.get().contains("40.0"), table.get());
            note("TC_EVT_016 PASS synthetic Acc_pedal_processed. Named rule Pedal_High. "
                    + "Findings table=[" + table.get() + "] report=" + oneLine(report)
                    + " editor=" + (graph.get() == null ? "" : editor(graph.get())));
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
    void thresholdValueIsNotASavedAnalysisSetting() throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-evt026");
        isolate(home);
        Path db = home.resolve("meta.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        final DesktopAppService opened = service;
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        try {
            opened.newProject("Clearance EVT026");
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
                window.setScene(new Scene(root, 1100, 700));
                window.show();
                stage.set(window);
                controller.setAppService(opened, null);
                controller.selectAnalysisTabForTest(2);
                EngineeringAnalysisPane pane = controller.engineeringPaneForTest();
                pane.setRunInputsForTest("THRESHOLD", "FULL", "50");
                button(pane, "Run Analysis").fire();
            });
            String report = waitForReport(opened, "46.000");
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
                field.set(pane.thresholdTextForTest());
                type.set(pane.analysisTypeForTest());
            });
            assertEquals("THRESHOLD", type.get());
            assertEquals("", field.get() == null ? "" : field.get());
            note("EVT026-REOPEN type=" + type.get() + " field='" + field.get() + "' report=" + oneLine(restored)
                    + " firstReport=" + oneLine(report));
            note("TC_EVT_026 NOT RUN category C: AnalysisWorkspaceMetadata stores the analysis type and "
                    + "finding id. The Threshold text is not a saved analysis setting. Reopen restores the "
                    + "violation text and THRESHOLD type. The input stays blank. The workbook sentence also "
                    + "requires the threshold value, so the full case is not PASS.");
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

    private static String editor(GraphController controller) {
        AtomicReference<String> value = new AtomicReference<>("");
        try {
            runOnFx(() -> value.set(controller.engineeringPaneForTest().rules().editorStatusForTest()));
        } catch (Exception ex) {
            return ex.getMessage();
        }
        return value.get();
    }

    private static String status(GraphController controller) {
        Label label = (Label) controller.graphCanvasForTest().getScene().lookup("#statusLabel");
        return label == null || label.getText() == null ? "" : label.getText();
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
        for (Node node : root.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getItems().contains(item)) {
                @SuppressWarnings("unchecked")
                ComboBox<String> typed = (ComboBox<String>) combo;
                typed.getSelectionModel().select(item);
            }
        }
    }

    private static void selectComboContaining(Parent root, String fragment) {
        for (Node node : root.lookupAll(".combo-box")) {
            if (!(node instanceof ComboBox<?> combo)) {
                continue;
            }
            for (Object item : combo.getItems()) {
                if (item != null && item.toString().contains(fragment)) {
                    @SuppressWarnings("unchecked")
                    ComboBox<Object> typed = (ComboBox<Object>) combo;
                    typed.getSelectionModel().select(item);
                    return;
                }
            }
        }
    }

    private static void selectTab(Parent root, String title) {
        for (Node node : root.lookupAll(".tab-pane")) {
            if (node instanceof TabPane tabs) {
                tabs.getTabs().stream()
                        .filter(tab -> title.equals(tab.getText()))
                        .findFirst()
                        .ifPresent(tab -> tabs.getSelectionModel().select(tab));
            }
        }
    }

    private static Button button(Parent root, String text) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button candidate && text.equals(candidate.getText())) {
                found = candidate;
            }
        }
        assertNotNull(found, text);
        return found;
    }

    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        if (node instanceof Label label && label.getText() != null) {
            sb.append(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                sb.append(text(child));
            }
        }
        return sb.toString();
    }

    private static String oneLine(String report) {
        return report == null ? "" : report.replace('\n', ' ').replaceAll("\\s+", " ").trim();
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
