package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.inventory.SourceInventoryWorkspace;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode is presented as two strategies inside one stage. The underlying mode identifiers,
 * their distinct workspaces and the Study path must survive the navigation refactor untouched.
 */
class DecodeStrategyPreservationTest {

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
    void decodeModeIdentifiersAreUnchanged() {
        assertEquals(2, DecisionWorkspaceMode.values().length);
        assertEquals("QUICK_DECODE", DecisionWorkspaceMode.QUICK_DECODE.name());
        assertEquals("DETAILED_MAPPING", DecisionWorkspaceMode.DETAILED_MAPPING.name());
    }

    @Test
    @Timeout(60)
    void autoAndCustomDecodeKeepDistinctWorkspacesUnderTheDecodeRoute() throws Exception {
        onFx((service, projectFiles, pane) -> {
            projectFiles.openSourceInventoryWorkspace();
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY,
                    projectFiles.activeWorkspaceForTest());

            pane.showDetailedMapping();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());

            // Re-entering Decode must resume the selected strategy, not reset it to Auto Decode.
            projectFiles.openSourcesWorkspace();
            projectFiles.openDecodeWorkspace();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING,
                    projectFiles.activeWorkspaceForTest());
        });
    }

    @Test
    @Timeout(60)
    void studyRemainsReachableInsideCustomDecode() throws Exception {
        onFx((service, projectFiles, pane) -> {
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            projectFiles.openDecodeWorkspace();
            assertNotNull(pane.studyHeaderCheckForTest(),
                    "Study / View Signals must remain available inside Custom Decode");
        });
    }

    @Test
    @Timeout(60)
    void inventoryAndDbcMappingRemainSubordinateWorkspaces() throws Exception {
        onFx((service, projectFiles, pane) -> {
            projectFiles.openSourceInventoryWorkspace();
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY,
                    projectFiles.activeWorkspaceForTest());
            projectFiles.openDbcMappingWorkspace();
            assertEquals(ProjectFilesWorkspaceKind.DBC_MAPPING,
                    projectFiles.activeWorkspaceForTest());
            // Both are reached through the Decode navigation destination, not their own.
            assertEquals(NavigationPage.DECODE, WorkspaceRoute.DECODE.page());
        });
    }

    private interface DecodeScenario {
        void run(DesktopAppService service,
                 ProjectFilesController projectFiles,
                 SourceInventoryWorkspace pane) throws Exception;
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
                SourceInventoryWorkspace pane =
                        (SourceInventoryWorkspace) view.lookup("#sourceInventoryWorkspace");
                assertNotNull(pane);

                scenario.run(service, projectFiles, pane);
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
