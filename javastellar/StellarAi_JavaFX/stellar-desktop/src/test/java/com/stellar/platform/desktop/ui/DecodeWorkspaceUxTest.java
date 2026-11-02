package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.inventory.SourceInventoryWorkspace;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.MeasurementSourceFormat;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode is one stage offering two strategies. The chooser must stay visible and stable, and both
 * strategies must land on the same Review &amp; Process route rather than starting processing.
 */
class DecodeWorkspaceUxTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(60)
    void decodeShowsOnlyOneStrategyChooser() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            Node strip = node(view, "sourceDecodeModeStrip");
            assertTrue(strip.isVisible());
            assertTrue(strip.isManaged());
            assertEquals(1, countShownToggles(view, "Auto Decode"));
            assertEquals(1, countShownToggles(view, "Custom Decode"));
            assertEquals(1, countShownLabels(view, "Decode Strategy"));
            assertEquals(0, countShownLabels(view, "Decode Mode"));
        });
    }

    @Test
    @Timeout(60)
    void noDuplicateDecodeModeStripVisible() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            SourceInventoryWorkspace pane =
                    (SourceInventoryWorkspace) view.lookup("#sourceInventoryWorkspace");
            assertNotNull(pane);
            assertFalse(pane.hasDuplicateDecodeModeStripForTest());
            assertFalse(collectText(view).contains("Decode Mode"));
            assertTrue(collectText(node(view, "sourceDecodeModeStrip")).contains("Decode Strategy"));
        });
    }

    @Test
    @Timeout(60)
    void topDecodeStrategyControlsWorkspaceMode() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING, projectFiles.activeWorkspaceForTest());

            ((ToggleButton) node(view, "sourceAutoDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void selectAutoShowsAutoWorkspace() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();
            ((ToggleButton) node(view, "sourceAutoDecodeButton")).fire();

            assertTrue(((ToggleButton) node(view, "sourceAutoDecodeButton")).isSelected());
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void selectCustomShowsCustomWorkspace() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();
            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();

            assertTrue(((ToggleButton) node(view, "sourceCustomDecodeButton")).isSelected());
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void switchingStrategyPreservesExistingModeSemantics() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            ((ToggleButton) node(view, "sourceAutoDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void leavingAndReturningToDecodePreservesSelectedStrategy() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();
            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();

            projectFiles.openSourcesWorkspace();
            assertFalse(node(view, "sourceDecodeModeStrip").isVisible());

            projectFiles.openDecodeWorkspace();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertTrue(((ToggleButton) node(view, "sourceCustomDecodeButton")).isSelected());
            assertTrue(node(view, "sourceDecodeModeStrip").isVisible());
            assertEquals(0, countShownLabels(view, "Decode Mode"));
        });
    }

    @Test
    @Timeout(60)
    void decodeShowsAutoAndCustomStrategiesInOneChooser() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            Node strip = node(view, "sourceDecodeModeStrip");
            assertTrue(strip.isVisible());
            assertEquals("Auto Decode", ((ToggleButton) node(view, "sourceAutoDecodeButton")).getText());
            assertEquals("Custom Decode", ((ToggleButton) node(view, "sourceCustomDecodeButton")).getText());
            assertTrue(collectText(strip).contains("Recommended"),
                    "Auto Decode is the recommended strategy and must say so");
        });
    }

    @Test
    @Timeout(60)
    void autoDecodeRemainsQuickDecodeAndCustomRemainsDetailedMapping() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING, projectFiles.activeWorkspaceForTest());

            ((ToggleButton) node(view, "sourceAutoDecodeButton")).fire();
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void theChooserReflectsTheStrategyCurrentlyInEffect() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            projectFiles.openDecodeWorkspace();

            assertTrue(((ToggleButton) node(view, "sourceCustomDecodeButton")).isSelected());
            assertFalse(((ToggleButton) node(view, "sourceAutoDecodeButton")).isSelected());
        });
    }

    @Test
    @Timeout(60)
    void leavingAndReenteringDecodeDoesNotSilentlyFallBackToAuto() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();
            ((ToggleButton) node(view, "sourceCustomDecodeButton")).fire();

            projectFiles.openSourcesWorkspace();
            projectFiles.openDecodeWorkspace();

            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertTrue(((ToggleButton) node(view, "sourceCustomDecodeButton")).isSelected());
        });
    }

    @Test
    @Timeout(60)
    void decodeReadinessIsStatedInEngineerFacingWords() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openDecodeWorkspace();

            String readiness = ((Label) node(view, "decodeReadinessLabel")).getText();
            assertNotNull(readiness);
            assertFalse(readiness.contains("QUICK_DECODE"), readiness);
            assertFalse(readiness.contains("DETAILED_MAPPING"), readiness);
            assertFalse(readiness.contains("MappingLifecycle"), readiness);
            assertFalse(readiness.toLowerCase().contains("processing plan"), readiness);
        });
    }

    @Test
    @Timeout(60)
    void bothStrategiesShareTheOneReviewAndProcessRoute() {
        // Review & Process is a transition checkpoint on the existing Processing host, never a
        // second processing page and never a direct jump into a running execution.
        assertEquals(NavigationPage.PROCESSING, WorkspaceRoute.REVIEW_AND_PROCESS.page());
        assertEquals("Review & Process", WorkspaceRoute.REVIEW_AND_PROCESS.label());
        assertTrue(WorkspaceRoute.REVIEW_AND_PROCESS.transientProcessingRoute());
        assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS,
                WorkspaceRoute.forPage(NavigationPage.PROCESSING));
    }

    @Test
    @Timeout(60)
    void autoDecodePrimaryActionIsReviewAndProcess() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
            projectFiles.openDecodeWorkspace();

            String text = collectText(view);
            assertTrue(text.contains("Review & Process"),
                    "Auto Decode must offer Review & Process as its primary action");
            assertFalse(text.contains("Proceed to Processing"),
                    "retired terminology must not survive anywhere in the Decode surface");
        });
    }

    private static Node node(BorderPane view, String id) {
        Node found = view.lookup("#" + id);
        assertNotNull(found, "missing node #" + id);
        return found;
    }

    private static int countShownToggles(Node root, String text) {
        int count = 0;
        if (root instanceof ToggleButton toggle
                && text.equals(toggle.getText())
                && actuallyShown(toggle)) {
            count++;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countShownToggles(child, text);
            }
        }
        return count;
    }

    private static int countShownLabels(Node root, String text) {
        int count = 0;
        if (root instanceof Label label
                && text.equals(label.getText())
                && actuallyShown(label)) {
            count++;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countShownLabels(child, text);
            }
        }
        return count;
    }

    private static boolean actuallyShown(Node node) {
        Node current = node;
        while (current != null) {
            if (!current.isVisible()) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private static String collectText(Node root) {
        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, sb);
            }
        }
    }

    private WorkspaceFile readySource(DesktopAppService service, String name) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "x");
        WorkspaceFile file = service.workspace().addFile(
                new WorkspaceFile(path.toAbsolutePath(), FileKind.MEASUREMENT_MF4, 4096));
        CanMessageInventory message =
                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 10, 8, 8, false);
        CanChannelInventory channel =
                new CanChannelInventory(1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(channel), List.of()));
        return file;
    }

    private interface DecodeScenario {
        void run(DesktopAppService service, ProjectFilesController projectFiles, BorderPane view)
                throws Exception;
    }

    private static void onFx(DecodeScenario scenario) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            DesktopAppService service = null;
            try {
                service = new DesktopAppService(new DesktopSession());
                FXMLLoader shellLoader = new FXMLLoader(
                        MainShellController.class.getResource("/fxml/main-shell.fxml"));
                shellLoader.load();
                MainShellController shell = shellLoader.getController();
                shell.init(service);

                FXMLLoader pfLoader = new FXMLLoader(
                        ProjectFilesController.class.getResource("/fxml/project-files-view.fxml"));
                BorderPane view = pfLoader.load();
                ProjectFilesController projectFiles = pfLoader.getController();
                projectFiles.setAppService(service, shell);

                scenario.run(service, projectFiles, view);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (service != null) service.shutdown();
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX scenario timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
