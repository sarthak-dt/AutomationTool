package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completed processing: one Explore Signals action per measurement, project-wide
 * Explore All Signals, and the Signals page scoped to the applied DBC.
 */
class PostProcessingExploreSignalsUiTest {

    private static final String APPLIED = "11_bit_OBD2 2.dbc";
    private static final String UNUSED = "unused_import.dbc";
    /** Content width at 1366 with the expanded sidebar. */
    private static final double CONTENT_WIDTH = 1366 - 250;

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(120)
    void eightMeasurementsExplorePerFileAndShowTheAppliedDbc() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("post-proc-scope");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            try {
                List<WorkspaceFile> files = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String name = String.format("4BE883CD_%08d_00000001-63C7A554.MF4", 975 + i);
                    files.add(addTwoChannelSource(service, temp, name));
                }
                WorkspaceFile applied = addDefinition(service, temp, APPLIED);
                addDefinition(service, temp, UNUSED);
                addDefinition(service, temp, "third_unused.dbc");
                for (WorkspaceFile file : files) {
                    service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                            SourceChannelKey.of(file.projectSourceId(), 1),
                            ChannelMappingStatus.CONFIRMED,
                            ChannelRecommendationKind.CLEAR_BEST_MATCH,
                            applied.definitionSourceId(), APPLIED,
                            applied.definitionSourceId(), APPLIED,
                            4, 1, 0.25));
                    service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                            SourceChannelKey.of(file.projectSourceId(), 2),
                            ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                            null, "", null, "", 1, 0, 0.0));
                }
                service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
                var model = service.processingSelectionModel();
                model.captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
                for (WorkspaceFile file : files) {
                    model.select(com.stellar.platform.domain.processing.plan.SourceChannelRef.of(file.id(), 1));
                    model.select(com.stellar.platform.domain.processing.plan.SourceChannelRef.of(file.id(), 2));
                }
                model.reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
                ProjectProcessingPlan plan = model.reviewedPlan();
                assertEquals(16, plan.jobs().size(), "8 measurements x 2 channels");
                model.beginExecution(plan);
                List<IndependentJobResult> results = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    WorkspaceFile file = files.get(i);
                    for (ProcessingJob job : plan.jobs()) {
                        if (!file.id().equals(job.projectSourceId())) {
                            continue;
                        }
                        boolean decoded = job.channelId() == 1;
                        InMemorySignalCatalogStore store = decoded
                                ? decodedStore(file.fileName(), "Sig" + i)
                                : nativeStore(file.fileName(), "Native" + i);
                        IndependentJobResult result = new IndependentJobResult(
                                job.key(),
                                decoded ? job.definitionSourceId().orElse(null) : null,
                                decoded ? APPLIED : "",
                                job.mappingLifecycle(),
                                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                                100, 100, 100, decoded ? 25 : 0, 0, 1,
                                1L, 2L, null, Duration.ofMillis(5), List.of(), "", store);
                        model.updateJobStatus(job.key(), result);
                        results.add(result);
                    }
                }
                assertEquals(16, results.size());
                model.completeExecution(new MultiSourceProcessingResult(
                        plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                        results, List.of(), Duration.ofSeconds(1)));

                ProcessingController processing = load(service, "/fxml/processing-view.fxml", CONTENT_WIDTH, 700);
                ProcessingWorkspacePane pane = processing.workspacePaneForTest();
                assertTrue(pane.usesQuickSourceResultPresentationForTest());
                assertFalse(pane.jobsTableForTest().isVisible(), "per-channel rows must not duplicate Explore");
                assertEquals(8, pane.quickSourceTableForTest().getItems().size());
                assertTrue(pane.quickSourceTableForTest().getItems().stream()
                        .allMatch(ProcessingWorkspacePane.QuickSourceView::canViewSignals));
                assertEquals("Explore All Signals", pane.openAnalysisButtonForTest().getText());
                assertLayoutFits(pane.quickSourceTableForTest(), CONTENT_WIDTH);

                pane.fireViewQuickSourceSignalsForTest(0);
                SignalsController signals = load(service, "/fxml/signals-view.fxml", CONTENT_WIDTH, 700);
                signals.refresh();
                assertEquals(files.get(0).fileName(), signals.measurementCardForTest());
                assertTrue(signals.measurementOptionsForTest().getFirst().startsWith("All Measurements (8)"));
                assertEquals(9, signals.measurementOptionsForTest().size());
                for (WorkspaceFile file : files) {
                    assertTrue(signals.measurementOptionsForTest().contains(file.fileName()), file.fileName());
                }
                assertScopedTo(service, files.get(0), 0);
                assertEquals(APPLIED, signals.dbcValueForTest());
                assertEquals("APPLIED DBC", signals.dbcCaptionForTest());
                assertEquals("2 Channels", signals.channelsCardForTest());

                SignalsController reopened = load(service, "/fxml/signals-view.fxml", CONTENT_WIDTH, 700);
                reopened.refresh();
                assertEquals(files.get(0).fileName(), reopened.measurementCardForTest(),
                        "reopening Signals must keep the per-file measurement");
                assertEquals(APPLIED, reopened.dbcValueForTest());
                signals = reopened;
                assertTrue(signals.dbcDetailForTest().contains("did not use a DBC"));
                assertFalse(signals.dbcValueForTest().contains(UNUSED));
                assertFalse(signals.dbcValueForTest().contains("DBCs"));
                assertTrue(signals.signalCountForTest().contains("for 1 measurement"));
                assertAnalyzeStaysOn(signals, service, files.get(0), files.get(1));

                pane.fireViewQuickSourceSignalsForTest(1);
                signals.refresh();
                assertEquals(files.get(1).fileName(), signals.measurementCardForTest());
                assertScopedTo(service, files.get(1), 1);
                assertEquals(APPLIED, signals.dbcValueForTest());

                pane.openAnalysisButtonForTest().fire();
                signals.refresh();
                assertTrue(signals.measurementCardForTest().contains("8 measurements"),
                        signals.measurementCardForTest());
                assertTrue(signals.signalCountForTest().contains("across 8 measurements"),
                        signals.signalCountForTest());
                assertEquals(16, service.signalsModel().displayedCatalog().size());
                assertEquals(APPLIED, signals.dbcValueForTest());
                assertFalse(signals.dbcValueForTest().contains(UNUSED));
                assertEquals("16 Channels", signals.channelsCardForTest());
            } finally {
                service.shutdown();
            }
        });
    }

    private static void assertScopedTo(DesktopAppService service, WorkspaceFile file, int index) {
        List<SignalCatalogEntry> shown = service.signalsModel().displayedCatalog();
        assertEquals(2, shown.size(), file.fileName());
        assertTrue(shown.stream().allMatch(entry -> file.id().equals(entry.key().sourceFile())),
                shown.stream().map(entry -> entry.key().sourceFile()).toList().toString());
        assertTrue(shown.stream().anyMatch(entry -> entry.signalName().equals("Sig" + index)));
        assertTrue(shown.stream().noneMatch(entry -> entry.signalName().equals("Sig" + (index == 0 ? 1 : 0))));
    }

    private static void assertAnalyzeStaysOn(
            SignalsController signals,
            DesktopAppService service,
            WorkspaceFile selected,
            WorkspaceFile other
    ) {
        service.signalsModel().addToWorkspace(service.signalsModel().displayedCatalog().stream()
                .map(SignalCatalogEntry::key)
                .toList());
        var resolution = signals.analysisResolutionForTest();
        assertTrue(resolution.canAnalyze(), resolution.explanation());
        assertEquals(selected.id(), resolution.measurementId());
        assertEquals(selected.fileName(), resolution.measurementFileName());
        assertFalse(resolution.droppedCrossMeasurementKeys());
        assertTrue(resolution.keys().stream().allMatch(key -> selected.id().equals(key.sourceFile())));
        assertTrue(resolution.keys().stream().noneMatch(key -> other.id().equals(key.sourceFile())));
    }

    private static void assertLayoutFits(TableView<?> table, double width) {
        double mins = 0;
        for (TableColumn<?, ?> column : table.getColumns()) {
            mins += column.getMinWidth();
        }
        assertTrue(mins <= width, "column mins " + mins + " exceed " + width);
        table.applyCss();
        table.layout();
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible()) {
                assertTrue(bar.getMax() <= bar.getVisibleAmount() + 1,
                        "processing table needs horizontal scrolling");
            }
        }
        int explores = 0;
        for (Node node : table.lookupAll(".processing-explore-signals")) {
            if (!(node instanceof Button button)) {
                continue;
            }
            explores++;
            Bounds buttonBounds = button.localToScene(button.getBoundsInLocal());
            Bounds tableBounds = table.localToScene(table.getBoundsInLocal());
            assertTrue(buttonBounds.getMaxX() <= tableBounds.getMaxX() + 1,
                    "Explore Signals is outside the table");
            assertTrue(buttonBounds.getMinX() >= tableBounds.getMinX() - 1);
        }
        assertEquals(8, explores, "one Explore Signals action per measurement");
    }

    private static InMemorySignalCatalogStore decodedStore(String file, String signal) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, APPLIED, 50);
        store.accept(List.of(new CanonicalSignal(
                signal, "OBD2", 10L, 0x7E8, 1, 1.0, 1L, "rpm", null, SourceReference.ofLine(1))));
        return store;
    }

    private static InMemorySignalCatalogStore nativeStore(String file, String signal) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, "", 50);
        store.accept(List.of(new CanonicalSignal(
                signal, InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE, 10L, 0L, 2, 1.0, 1L, "V", null,
                SourceReference.ofLine(1))));
        return store;
    }

    private static WorkspaceFile addTwoChannelSource(DesktopAppService service, Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channels = List.of(
                new CanChannelInventory(1, "Channel 1", 100L, 4, 4, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x7E8L, false), 40, 8, 8, false))),
                new CanChannelInventory(2, "Channel 2", 50L, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(2, 0x200L, false), 10, 8, 8, false))));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                2, 150L, 5, true, channels, List.of()));
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path dir, String name) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static <T> T load(DesktopAppService service, String fxml, double width, double height) throws Exception {
        FXMLLoader loader = new FXMLLoader(PostProcessingExploreSignalsUiTest.class.getResource(fxml));
        Parent root = loader.load();
        T controller = loader.getController();
        if (controller instanceof ProcessingController processing) {
            processing.setAppService(service, null);
        } else if (controller instanceof SignalsController signals) {
            signals.setAppService(service, null);
        }
        Scene scene = new Scene(root, width, height);
        var css = PostProcessingExploreSignalsUiTest.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.show();
        root.applyCss();
        root.layout();
        return controller;
    }

    private static void runOnFx(FxAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(90, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            if (error.get() instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
