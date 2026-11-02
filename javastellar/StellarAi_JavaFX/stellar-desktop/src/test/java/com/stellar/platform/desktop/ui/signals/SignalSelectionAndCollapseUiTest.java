package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScopeTest;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalSelectionAndCollapseUiTest {

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
    @Timeout(40)
    void selectAllRespectsMeasurementAndSearchAndDeselectClearsOnlyThatSet() throws Exception {
        run(() -> {
            SignalsScreenModel model = new SignalsScreenModel();
            model.replaceCatalog(MeasurementCatalogScopeTest.fourFileStore());
            SignalBrowserPane browser = new SignalBrowserPane();
            ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
            browser.setModel(model);
            workspace.setModel(model);
            browser.refreshFromModel();
            browser.setOnSelectAll(() -> workspace.addPhysicalKeys(keys(browser)));
            browser.setOnDeselectAll(() -> workspace.removePhysicalKeys(keys(browser)));

            browser.selectMeasurementForTest("MF4_A.mf4");
            browser.runFilterNow();
            int reloads = workspace.reloadCountForTest();
            browser.selectAllButtonForTest().fire();
            assertEquals(reloads + 1, workspace.reloadCountForTest());
            assertFalse(model.workspaceKeys().isEmpty());
            assertTrue(model.workspaceKeys().stream().allMatch(key -> "src-A".equals(key.sourceFile())));
            assertTrue(model.workspaceKeys().stream().noneMatch(key -> "src-B".equals(key.sourceFile())));

            browser.searchField().setText("UniqueA");
            browser.runFilterNow();
            assertTrue(browser.filteredEntries().stream().allMatch(entry -> "UniqueA".equals(entry.key().signalName())));
            browser.deselectAllButtonForTest().fire();
            assertTrue(model.workspaceKeys().stream().noneMatch(key -> "UniqueA".equals(key.signalName())));
            assertTrue(model.workspaceKeys().stream().anyMatch(key -> "src-A".equals(key.sourceFile())));
            assertEquals(reloads + 2, workspace.reloadCountForTest());

            browser.searchField().clear();
            browser.selectMeasurementForTest(MeasurementCatalogScope.allMeasurementsLabel(4));
            browser.runFilterNow();
            browser.searchField().setText("UniqueB");
            browser.runFilterNow();
            browser.selectAllButtonForTest().fire();
            assertTrue(model.workspaceKeys().stream().anyMatch(key -> "src-B".equals(key.sourceFile())));
            assertTrue(model.workspaceKeys().stream().noneMatch(key -> "src-C".equals(key.sourceFile())));
        });
    }

    @Test
    @Timeout(40)
    void collapsingTheBrowserExpandsTheWorkspaceWithoutReloadingSamples() throws Exception {
        run(() -> {
            FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root, 1366, 768);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            SignalsController controller = loader.getController();
            SignalBrowserPane browser = controller.browserForCollapseTest();
            ActiveSignalWorkspacePane workspace = controller.workspaceForCollapseTest();
            HBox workbench = controller.workbenchForCollapseTest();
            double browserWidth = browser.getPrefWidth();
            int reloads = workspace.reloadCountForTest();
            var selectAll = browser.selectAllButtonForTest();
            assertEquals("Select All", selectAll.getText());
            assertEquals("Deselect All", browser.deselectAllButtonForTest().getText());
            assertEquals("Filters", browser.moreFiltersTextForTest());
            assertFalse(browser.measurementChooserInDefaultColumnForTest());
            assertFalse(browser.dbcFilterInDefaultColumnForTest());
            assertEquals("Hide Duplicates", browser.hideDuplicatesTextForTest());
            assertTrue(selectAll.getWidth() + 0.5 >= selectAll.prefWidth(-1));
            assertTrue(browser.deselectAllButtonForTest().getWidth() + 0.5 >= browser.deselectAllButtonForTest().prefWidth(-1));
            assertTrue(browser.collapseButtonInHeaderForTest());
            assertTrue(workspace.commandLabelsReadableForTest());
            assertTrue(selectAll.getBoundsInParent().getMaxX() <= browser.getWidth() + 1);
            assertTrue(browser.deselectAllButtonForTest().getBoundsInParent().getMaxX() <= browser.getWidth() + 1);
            assertTrue(browser.collapseButtonForTest().getBoundsInParent().getMaxX() <= browser.getWidth() + 1);

            controller.collapseSignalBrowserForTest();
            assertTrue(controller.signalBrowserCollapsedForTest());
            assertFalse(controller.signalBrowserAttachedForTest());
            assertFalse(workbench.getChildren().contains(browser));
            assertTrue(workbench.getChildren().stream().anyMatch(node -> node.getStyleClass().contains("signal-panel-rail")));
            assertTrue(workbench.getChildren().contains(workspace));
            assertEquals(Priority.ALWAYS, HBox.getHgrow(workspace));
            assertEquals(reloads, workspace.reloadCountForTest());

            controller.expandSignalBrowserForTest();
            assertFalse(controller.signalBrowserCollapsedForTest());
            assertTrue(controller.signalBrowserAttachedForTest());
            assertEquals(0, workbench.getChildren().indexOf(browser));
            assertEquals(browserWidth, browser.getPrefWidth(), 0.1);
            assertEquals(reloads, workspace.reloadCountForTest());
        });
    }

    private static List<SignalCatalogKey> keys(SignalBrowserPane browser) {
        return browser.filteredEntries().stream().map(entry -> entry.key()).toList();
    }

    private static void run(FxAction action) throws Exception {
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
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
