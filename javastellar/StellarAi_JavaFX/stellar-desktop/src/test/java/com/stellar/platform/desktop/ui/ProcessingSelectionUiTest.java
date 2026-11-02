package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingSelectionUiTest {

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
    @Timeout(60)
    void checkboxSelectsSourceChannelRefAndReviewFlowWorks() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-sel-ui");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile a = addReadySource(service, temp, "alpha.mf4", 1, 1000L);
            WorkspaceFile b = addReadySource(service, temp, "beta.mf4", 1, 2000L);
            addConfirmedMapping(service, a, 1);
            addConfirmedMapping(service, b, 1);

            ProcessingController controller = loadController(service);
            ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
            ProcessingSelectionPane pane = controller.selectionPaneForTest();
            Stage selectionStage = showDetached(pane, 1280, 800);

            List<CheckBox> boxes = findCheckBoxes(pane);
            assertTrue(boxes.size() >= 2, "expected channel checkboxes");

            CheckBox first = boxes.get(0);
            CheckBox second = boxes.get(1);
            SourceChannelRef ref1 = (SourceChannelRef) first.getUserData();
            SourceChannelRef ref2 = (SourceChannelRef) second.getUserData();
            assertNotNull(ref1);
            assertNotNull(ref2);
            assertFalse(ref1.equals(ref2));

            first.setSelected(true);
            assertTrue(model.selection().contains(ref1));
            assertEquals(1, model.selection().size());

            // same channel number across sources => 2 selections
            second.setSelected(true);
            assertEquals(2, model.selection().size());
            assertTrue(model.selection().contains(ref1));
            assertTrue(model.selection().contains(ref2));

            // multi-channel same source
            WorkspaceFile multi = addReadySource(service, temp, "multi.mf4", 2, 500L);
            addConfirmedMapping(service, multi, 1);
            addConfirmedMapping(service, multi, 2);
            controller.refresh();
            model.clearSelection();
            List<CheckBox> multiBoxes = findCheckBoxes(controller.selectionPaneForTest()).stream()
                    .filter(cb -> {
                        SourceChannelRef r = (SourceChannelRef) cb.getUserData();
                        return r != null && r.projectSourceId().equals(multi.id());
                    })
                    .toList();
            assertEquals(2, multiBoxes.size());
            multiBoxes.get(0).setSelected(true);
            multiBoxes.get(1).setSelected(true);
            assertEquals(2, model.selection().size());

            Button review = (Button) controller.selectionPaneForTest().lookup("#review-plan-button");
            assertNotNull(review);
            assertFalse(review.isDisabled());
            review.fire();
            assertEquals(ProcessingPageMode.PLAN_REVIEW, model.mode());
            assertSame(controller.workspacePaneForTest(),
                    controller.processingHostForTest().getChildren().getFirst());
            assertEquals(2, model.reviewedPlan().jobs().size());
            assertEquals(ProcessingWorkspacePhase.REVIEW, controller.workspacePaneForTest().phaseForTest());
            selectionStage.close();
        });
    }

    @Test
    @Timeout(60)
    void geometryKeepsKeyControlsVisibleWithoutPageHScroll() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-sel-geo");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile src = addReadySource(service, temp, "geometry.mf4", 1, 100L);
            addConfirmedMapping(service, src, 1);

            for (double[] size : List.of(new double[]{1600, 900}, new double[]{1920, 1080}, new double[]{1366, 768})) {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/processing-view.fxml"));
                Parent root = loader.load();
                ProcessingController controller = loader.getController();
                controller.setAppService(service, null);
                service.processingSelectionModel().clearSelection();
                service.processingSelectionModel().select(SourceChannelRef.of(src.id(), 1));
                controller.refresh();

                Scene scene = new Scene(root, size[0], size[1]);
                var css = getClass().getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.setWidth(size[0]);
                stage.setHeight(size[1]);
                stage.show();
                root.applyCss();
                root.resize(size[0], size[1]);
                root.layout();

                ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
                assertNotNull(workspace.lookup("#processing-jobs-table"));
                Button run = workspace.runButtonForTest();
                assertNotNull(run);
                assertTrue(run.isVisible());
                Bounds runBounds = run.localToScene(run.getBoundsInLocal());
                assertTrue(runBounds.getMaxX() <= size[0] + 2.0,
                        "run button clipped at " + size[0]);
                Button back = workspace.backButtonForTest();
                assertTrue(back.isVisible());
                Bounds backBounds = back.localToScene(back.getBoundsInLocal());
                assertTrue(backBounds.getMaxX() <= size[0] + 2.0,
                        "back button clipped at " + size[0]);
                stage.close();
            }
        });
    }

    private static ProcessingController loadController(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(ProcessingSelectionUiTest.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController controller = loader.getController();
        controller.setAppService(service, null);
        Scene scene = new Scene(root, 1280, 800);
        var css = ProcessingSelectionUiTest.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return controller;
    }

    private static Stage showDetached(Parent pane, double width, double height) {
        Scene scene = new Scene(pane, width, height);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.show();
        pane.applyCss();
        pane.layout();
        return stage;
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path temp, String name, int channels, long framesPerChannel
    ) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channelList = new ArrayList<>();
        for (int i = 1; i <= channels; i++) {
            channelList.add(new CanChannelInventory(
                    i, "Channel " + i, framesPerChannel, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false), 10, 8, 8, false))));
        }
        long totalFrames = framesPerChannel * channels;
        CanSourceInventory inventory = new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, totalFrames, channels, true, channelList, List.of());
        service.sourceInventoryModel().put(file.id(), inventory);
        return file;
    }

    private static void addConfirmedMapping(DesktopAppService service, WorkspaceFile measurement, int channelId) {
        DefinitionSourceId defId = DefinitionSourceId.generate();
        // Ensure definitionPresent by registering a definition file with this id when possible.
        // If not present, assembler marks definitionPresent=false — still fine for selection UI.
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId,
                "map.dbc",
                defId,
                "map.dbc",
                1, 1, 1.0
        ));
    }

    private static List<CheckBox> findCheckBoxes(Node root) {
        List<CheckBox> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<CheckBox> out) {
        if (node instanceof CheckBox cb && cb.getStyleClass().contains("processing-channel-checkbox")) {
            out.add(cb);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static List<ScrollPane> findScrollPanes(Node node) {
        List<ScrollPane> out = new ArrayList<>();
        collectScroll(node, out);
        return out;
    }

    private static void collectScroll(Node node, List<ScrollPane> out) {
        if (node instanceof ScrollPane sp) {
            out.add(sp);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectScroll(child, out);
            }
        }
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
        assertTrue(done.await(50, TimeUnit.SECONDS), "FX action timed out");
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