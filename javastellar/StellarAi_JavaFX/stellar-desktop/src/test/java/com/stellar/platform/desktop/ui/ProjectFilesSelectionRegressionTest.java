package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.processing.ParserRegistry;
import javafx.application.Platform;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for Project Files TableView StackOverflowError caused by:
 * definition selection → refresh → select → selection listener → refresh …
 */
class ProjectFilesSelectionRegressionTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void shouldChangeSelection_onlyWhenDifferent() {
        assertFalse(ProjectFilesTableSync.shouldChangeSelection("a", "a"));
        assertTrue(ProjectFilesTableSync.shouldChangeSelection(null, "a"));
        assertTrue(ProjectFilesTableSync.shouldChangeSelection("a", "b"));
        assertTrue(ProjectFilesTableSync.shouldChangeSelection("a", null));
        assertFalse(ProjectFilesTableSync.shouldChangeSelection(null, null));
    }

    @Test
    void replaceItemsAndSyncSelection_skipsReselectWhenAlreadyCorrect() throws Exception {
        runOnFxThread(() -> {
            WorkspaceFile file = new WorkspaceFile(Path.of("a.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 1);
            TableView<WorkspaceFile> table = new TableView<>();
            table.getItems().add(file);
            table.getSelectionModel().select(file);

            AtomicInteger selectEvents = new AtomicInteger();
            table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> selectEvents.incrementAndGet());

            boolean changed = ProjectFilesTableSync.replaceItemsAndSyncSelection(
                    table,
                    java.util.List.of(file),
                    file.id()
            );

            assertFalse(changed, "must no-op when items and selection already match");
            assertEquals(0, selectEvents.get(), "no selection churn when desired id already selected");
            assertEquals(file, table.getSelectionModel().getSelectedItem());
            assertEquals(1, table.getItems().size());
        });
    }

    @Test
    @Timeout(15)
    void definitionSelectionRefreshCycle_doesNotStackOverflow() throws Exception {
        runOnFxThread(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile measurement = service.addPath(Path.of("engine.asc").toAbsolutePath());
            // Force kind via direct workspace file for unit isolation
            if (measurement.kind() != FileKind.MEASUREMENT_ASC) {
                measurement = new WorkspaceFile(Path.of("engine.asc").toAbsolutePath(), FileKind.MEASUREMENT_ASC, 1);
                service.workspace().addFile(measurement);
            }
            WorkspaceFile definition = new WorkspaceFile(Path.of("engine.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 1);
            service.workspace().addFile(definition);
            service.selectMeasurement(measurement.id());
            service.selectDefinition(definition.id());

            TableView<WorkspaceFile> measurementTable = new TableView<>();
            TableView<WorkspaceFile> definitionTable = new TableView<>();

            AtomicInteger refreshDepth = new AtomicInteger();
            AtomicInteger maxDepth = new AtomicInteger();
            AtomicInteger refreshCount = new AtomicInteger();

            Runnable refresh = new Runnable() {
                @Override
                public void run() {
                    int depth = refreshDepth.incrementAndGet();
                    maxDepth.updateAndGet(prev -> Math.max(prev, depth));
                    refreshCount.incrementAndGet();
                    try {
                        if (depth > 20) {
                            throw new IllegalStateException("recursive refresh detected, depth=" + depth);
                        }
                        String desiredMeas = service.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
                        String desiredDef = service.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
                        ProjectFilesTableSync.replaceItemsAndSyncSelection(
                                measurementTable, service.workspace().measurementFiles(), desiredMeas);
                        ProjectFilesTableSync.replaceItemsAndSyncSelection(
                                definitionTable, service.workspace().definitionFiles(), desiredDef);
                    } finally {
                        refreshDepth.decrementAndGet();
                    }
                }
            };

            // Fixed listener design: update model when needed; never call refresh from selection.
            definitionTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                if (n == null) {
                    return;
                }
                String currentId = service.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
                if (!java.util.Objects.equals(currentId, n.id())) {
                    service.selectDefinition(n.id());
                }
                measurementTable.refresh();
            });

            refresh.run();
            assertEquals(definition, definitionTable.getSelectionModel().getSelectedItem());

            // Simulate the previously failing path: select definition then refresh repeatedly
            for (int i = 0; i < 25; i++) {
                definitionTable.getSelectionModel().clearSelection();
                definitionTable.getSelectionModel().select(definition);
                refresh.run();
            }

            assertTrue(maxDepth.get() <= 1, "refresh must not re-enter; maxDepth=" + maxDepth.get());
            assertEquals(definition.id(), service.workspace().selectedDefinition().orElseThrow().id());
            assertEquals(definition, definitionTable.getSelectionModel().getSelectedItem());
            assertFalse(service.workspace().measurementFiles().isEmpty());
            assertFalse(service.workspace().definitionFiles().isEmpty());
        });
    }

    @Test
    @Timeout(15)
    void brokenPattern_wouldHaveReentered_documentingRegression() throws Exception {
        // Proves the old pattern (listener → refresh → select) re-enters; kept as a guardrail.
        runOnFxThread(() -> {
            WorkspaceFile definition = new WorkspaceFile(Path.of("x.dbc").toAbsolutePath(), FileKind.DEFINITION_DBC, 1);
            TableView<WorkspaceFile> table = new TableView<>();
            AtomicInteger depth = new AtomicInteger();
            AtomicInteger maxDepth = new AtomicInteger();

            Runnable brokenRefresh = new Runnable() {
                @Override
                public void run() {
                    int d = depth.incrementAndGet();
                    maxDepth.updateAndGet(p -> Math.max(p, d));
                    try {
                        if (d > 8) {
                            return;
                        }
                        table.getItems().setAll(java.util.List.of(definition));
                        table.getSelectionModel().select(definition);
                    } finally {
                        depth.decrementAndGet();
                    }
                }
            };

            table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    brokenRefresh.run();
                }
            });

            brokenRefresh.run();
            assertTrue(maxDepth.get() > 1, "sanity: old pattern re-enters (maxDepth=" + maxDepth.get() + ")");
        });
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX action timed out");
        assertNull(error.get(), () -> "FX action failed: " + error.get());
    }
}
