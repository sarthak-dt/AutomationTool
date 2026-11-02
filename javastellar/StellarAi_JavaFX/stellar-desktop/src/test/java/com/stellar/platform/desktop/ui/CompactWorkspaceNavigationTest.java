package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.inventory.SourceInventoryWorkspace;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactWorkspaceNavigationTest {

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
    @Timeout(45)
    void shellUsesCompactWorkflowAndProjectFilesBreadcrumbHierarchy() throws Exception {
        runOnFx(() -> { try {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            FXMLLoader shellLoader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            Parent shellRoot = shellLoader.load();
            MainShellController shell = shellLoader.getController();
            shell.init(service);

            assertTrue(shell.hasProjectStatusChipForTest());
            assertEquals(NavigationPage.HOME, shell.visiblePage());

            FXMLLoader pfLoader = new FXMLLoader(ProjectFilesController.class.getResource("/fxml/project-files-view.fxml"));
            BorderPane projectFiles = pfLoader.load();
            ProjectFilesController pf = pfLoader.getController();
            pf.setAppService(service, shell);

            assertNotNull(projectFiles.lookup(".project-files-chrome"));
            assertNotNull(projectFiles.lookup(".project-files-breadcrumb"));
            assertNotNull(projectFiles.lookup("#workspaceNavBar"));

            ToggleButton sources = (ToggleButton) projectFiles.lookup("#sourcesWorkspaceTab");
            ToggleButton inventory = (ToggleButton) projectFiles.lookup("#sourceInventoryWorkspaceTab");
            ToggleButton mapping = (ToggleButton) projectFiles.lookup("#dbcMappingWorkspaceTab");
            assertNotNull(sources);
            assertNotNull(inventory);
            assertNotNull(mapping);
            assertEquals("Sources", sources.getText());
            assertEquals("Auto Decode", inventory.getText());
            assertFalse(inventory.isManaged(), "decode tab stays hidden until a mode is opened from Sources");
            assertFalse(mapping.isManaged());

            ToggleButton autoMode = (ToggleButton) projectFiles.lookup("#sourceAutoDecodeButton");
            ToggleButton customMode = (ToggleButton) projectFiles.lookup("#sourceCustomDecodeButton");
            assertNotNull(autoMode);
            assertNotNull(customMode);
            assertEquals("Auto Decode", autoMode.getText());
            assertEquals("Custom Decode", customMode.getText());

            pf.openSourceInventoryWorkspace();
            assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
            assertTrue(inventory.isManaged());
            assertEquals("Auto Decode", inventory.getText());
            SourceInventoryWorkspace workspace =
                    (SourceInventoryWorkspace) projectFiles.lookup("#sourceInventoryWorkspace");
            assertNotNull(workspace);
            assertFalse(workspace.hasDuplicateWorkspaceTitleForTest());
            assertTrue(workspace.getStyleClass().contains("decision-workspace-root"));
            assertNotNull(workspace.actionBarForTest());
            assertEquals("Review & Process", workspace.actionBarForTest().proceedButtonForTest().getText());

            workspace.showDetailedMapping();
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
            assertEquals("Custom Decode", inventory.getText());

            pf.openDbcMappingWorkspace();
            assertTrue(workspace.isVisible());
            assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());

            Stage stage = new Stage();
            stage.setScene(new Scene(projectFiles, 1600, 900));
            stage.show();
            projectFiles.applyCss();
            projectFiles.resize(1600, 780);
            projectFiles.layout();
            var chrome = projectFiles.lookup(".project-files-chrome");
            assertNotNull(chrome);
            assertTrue(chrome.getLayoutBounds().getHeight() <= 90,
                    "project files chrome too tall: " + chrome.getLayoutBounds().getHeight());
            assertEquals(Priority.ALWAYS, VBox.getVgrow(workspace));

            pf.openSourceInventoryWorkspace();
            pf.refresh();
            projectFiles.layout();
            assertEquals(ProjectFilesWorkspaceKind.SOURCE_INVENTORY, pf.activeWorkspaceForTest());
            assertFalse(pf.activeWorkspaceForTest().showLegacySelectionBar());
            assertFalse(pf.processingActionBarForTest().isManaged());
            assertFalse(pf.processingActionBarForTest().isVisible());
            assertFalse(pf.processingActionBarForTest().lookup(".button") != null
                    && pf.processingActionBarForTest().isManaged());
            String barText = pf.processingActionBarForTest().processButton().getText();
            assertEquals("Process Selected", barText);
            assertTrue(workspace.lookupAll(".decision-action-bar").size() >= 1);

            pf.openSourcesWorkspace();
            pf.refresh();
            assertEquals(ProjectFilesWorkspaceKind.SOURCES, pf.activeWorkspaceForTest());
            assertFalse(pf.activeWorkspaceForTest().showLegacySelectionBar());
            assertFalse(pf.processingActionBarForTest().isManaged());
            assertFalse(pf.processingActionBarForTest().isVisible());
            stage.close(); } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }

    @Test
    @Timeout(20)
    void projectFilesFxmlOmitsGiantSectionHeader() throws Exception {
        try (var in = ProjectFilesController.class.getResourceAsStream("/fxml/project-files-view.fxml")) {
            assertNotNull(in);
            String fxml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(fxml.contains("project-files-breadcrumb"));
            assertTrue(fxml.contains("workspaceTitleLabel"));
            assertFalse(fxml.contains("styleClass=\"section-header\""));
            assertTrue(fxml.contains("Sources"));
            assertTrue(fxml.contains("Auto Decode"));
            assertTrue(fxml.contains("Custom Decode"));
            assertFalse(fxml.contains("Decision Workspace"));
            assertTrue(fxml.contains("sourceAutoDecodeButton"));
            assertTrue(fxml.contains("sourceCustomDecodeButton"));
            assertTrue(fxml.contains("sourceInventoryWorkspace"));
        }
    }

    @Test
    @Timeout(20)
    void mainShellFxmlRemovesTallWorkflowBand() throws Exception {
        try (var in = MainShellController.class.getResourceAsStream("/fxml/main-shell.fxml")) {
            assertNotNull(in);
            String fxml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(fxml.contains("projectStatusButton"));
            assertTrue(fxml.contains("workflow-status-button"));
            assertFalse(fxml.contains("fx:id=\"workflowBar\""));
            assertFalse(fxml.contains("fx:id=\"stepIngest\""));
            assertFalse(fxml.contains("fx:id=\"workflowProgressLabel\""));
        }
    }

    private static void runOnFx(Runnable action) throws Exception {
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
        assertTrue(done.await(40, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            if (error.get() instanceof Error err) {
                throw err;
            }
            throw new AssertionError(error.get());
        }
    }
}