package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
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
import javafx.scene.control.Labeled;
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
 * Sources is a measurement-source workspace: a real empty state, exactly one dominant next action,
 * and no decode or processing chrome bleeding into it.
 */
class SourcesWorkspaceUiTest {

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
    void newProjectSourcesShowsEmptyStateAndDisablesContinueToDecode() throws Exception {
        onFx((service, projectFiles, view) -> {
            projectFiles.openSourcesWorkspace();

            assertTrue(projectFiles.sourcesEmptyStateForTest().isVisible(),
                    "a project with no sources must explain what to do");
            assertFalse(projectFiles.sourcesWorkbenchForTest().isVisible(),
                    "no empty technical tables before a source exists");
            assertFalse(projectFiles.sourcesToolbarForTest().isVisible());

            assertTrue(projectFiles.sourcesActionBarForTest().isVisible(),
                    "the next action stays visible so the journey is discoverable");
            assertTrue(projectFiles.continueToDecodeButtonForTest().isDisabled());
        });
    }

    @Test
    @Timeout(60)
    void emptyStateOffersBrowseAndNamesTheSupportedFormats() throws Exception {
        onFx((service, projectFiles, view) -> {
            projectFiles.openSourcesWorkspace();

            assertNotNull(projectFiles.sourcesEmptyBrowseButtonForTest());
            String text = collectText(projectFiles.sourcesEmptyStateForTest());
            assertTrue(text.contains("Drop MF4, ASC, BLF, DAT or LOG files here"), text);
            assertTrue(text.contains("MF4") && text.contains("ASC") && text.contains("BLF")
                    && text.contains("DAT") && text.contains("LOG"), text);
        });
    }

    @Test
    @Timeout(60)
    void usableSourceReplacesEmptyStateAndEnablesContinueToDecode() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openSourcesWorkspace();

            assertFalse(projectFiles.sourcesEmptyStateForTest().isVisible());
            assertTrue(projectFiles.sourcesWorkbenchForTest().isVisible());
            assertFalse(projectFiles.continueToDecodeButtonForTest().isDisabled());
        });
    }

    @Test
    @Timeout(60)
    void sourcesReadinessIsStatedInUserFacingWords() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openSourcesWorkspace();

            String readiness = projectFiles.sourcesReadinessLabelForTest().getText();
            assertTrue(readiness.contains("1 Ready"), readiness);
            assertFalse(readiness.contains("INVENTORY_READY"), "internal enums must not reach the user");
            assertFalse(readiness.contains("QUICK_DECODE"), readiness);
        });
    }

    @Test
    @Timeout(60)
    void reachingDecodeFromSourcesKeepsTheChosenStrategy() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            projectFiles.openSourcesWorkspace();
            assertFalse(projectFiles.continueToDecodeButtonForTest().isDisabled());

            projectFiles.openDecodeWorkspace();

            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode(),
                    "arriving at Decode must not silently reset the chosen strategy");
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING, projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void sourcesDoesNotShowTheDecodeStrategyChooser() throws Exception {
        onFx((service, projectFiles, view) -> {
            readySource(service, "run01.mf4");
            projectFiles.openSourcesWorkspace();
            assertFalse(node(view, "sourceDecodeModeStrip").isVisible(),
                    "Sources must not become a decode page");

            projectFiles.openDecodeWorkspace();
            assertTrue(node(view, "sourceDecodeModeStrip").isVisible(),
                    "the strategy chooser belongs to Decode and must stay visible there");
            assertFalse(projectFiles.sourcesActionBarForTest().isVisible(),
                    "Continue to Decode is a Sources action only");
        });
    }

    @Test
    @Timeout(60)
    void unavailableSourceIsShownAsDegradedRatherThanReady() throws Exception {
        onFx((service, projectFiles, view) -> {
            WorkspaceFile file = readySource(service, "moved.mf4");
            service.sourceAvailabilityModel().markUnavailable(file.projectSourceId());
            projectFiles.openSourcesWorkspace();

            assertTrue(projectFiles.continueToDecodeButtonForTest().isDisabled());
            String readiness = projectFiles.sourcesReadinessLabelForTest().getText();
            assertTrue(readiness.contains("Unavailable"), readiness);
        });
    }

    private static Node node(BorderPane view, String id) {
        Node found = view.lookup("#" + id);
        assertNotNull(found, "missing node #" + id);
        return found;
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

    /** A source whose channel discovery has completed, backed by a real file so availability holds. */
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

    private interface SourcesScenario {
        void run(DesktopAppService service, ProjectFilesController projectFiles, BorderPane view)
                throws Exception;
    }

    private static void onFx(SourcesScenario scenario) throws Exception {
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
