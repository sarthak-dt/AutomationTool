package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProjectJourneyState;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dashboard is the project entry point: recent projects, open/new, no Projects nav page.
 */
class DashboardProjectsUiTest {
    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        System.setProperty("stellarai.dashboard.sync", "true");
    }

    @Test
    @Timeout(60)
    void dashboardListsRecentProjectsMarksActiveAndDoesNotLoadGraph() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            String previousDb = System.getProperty("stellarai.metadata.db");
            DesktopAppService service = null;
            MainShellController shell = null;
            try {
                Path home = temp.resolve("home");
                Path db = temp.resolve("meta.db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
                service = new DesktopAppService(new DesktopSession(), store);
                service.newProject("Vehicle Durability Test");
                service.newProject("Road Validation");
                assertEquals(2, service.savedProjects().size());
                assertEquals(2, service.projectDashboardSummaries().size());

                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                shell = loader.getController();
                shell.init(service);

                assertTrue(shell.pageLoadedForTest(NavigationPage.HOME));
                assertEquals(1, shell.loadedPageCountForTest());
                assertFalse(shell.pageLoadedForTest(NavigationPage.PROJECTS_HOME));
                assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));
                assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertFalse(shell.pageLoadedForTest(NavigationPage.PROCESSING));

                shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
                assertTrue(shell.pageLoadedForTest(NavigationPage.PROJECTS_HOME));
                assertEquals(2, shell.loadedPageCountForTest());

                Object controller = shell.controllersForTest().get(NavigationPage.PROJECTS_HOME);
                assertTrue(controller instanceof DashboardController);
                DashboardController dashboard = (DashboardController) controller;
                dashboard.refresh();

                assertNotNull(dashboard.kpiRowForTest());
                assertEquals(5, dashboard.kpiRowForTest().getChildren().size());
                assertNotNull(dashboard.searchFieldForTest());

                GridPane table = dashboard.recentProjectsTableForTest();
                assertNotNull(table);
                assertEquals(RecentProjectsTableLayout.COLUMN_COUNT, table.getColumnConstraints().size());
                assertEquals(RecentProjectsTableLayout.COLUMN_COUNT, countNodesInRow(table, 0));
                assertEquals(2, dataRowCount(table));
                assertTrue(rowTitles(table).stream().anyMatch(t -> t.contains("Road Validation")));
                assertTrue(rowTitles(table).stream().anyMatch(t -> t.contains("Vehicle Durability Test")));
                assertTrue(findButtons(table).stream().anyMatch(b -> "•••".equals(b.getText())));
                assertTrue(identityRowsAreClickable(table),
                        "project identity/name must be a click target to open/resume");

                // Every card action is the project's state-driven next step, never a generic "Open".
                assertTrue(findButtons(table).stream()
                        .filter(b -> !"•••".equals(b.getText()))
                        .allMatch(b -> ProjectJourneyState.NEW.primaryActionLabel().equals(b.getText())));
                assertEquals(ProjectJourneyState.NEW, dashboard.activeJourneyStateForTest());
                assertEquals(ProjectJourneyState.NEW.primaryActionLabel(),
                        dashboard.resumeButtonForTest().getText());
                assertEquals(ProjectJourneyState.NEW.statusLabel().display(),
                        dashboard.journeyStatusBadgeForTest().getText());

                dashboard.searchFieldForTest().setText("road");
                assertEquals(1, dataRowCount(dashboard.recentProjectsTableForTest()));
                assertTrue(rowTitles(dashboard.recentProjectsTableForTest()).get(0).contains("Road Validation"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (shell != null) shell.dispose();
                if (service != null) service.shutdown();
                if (previousHome == null) System.clearProperty("stellarai.home");
                else System.setProperty("stellarai.home", previousHome);
                if (previousDb == null) System.clearProperty("stellarai.metadata.db");
                else System.setProperty("stellarai.metadata.db", previousDb);
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    @Timeout(60)
    void dashboardRendersNoActiveProjectState() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            String previousDb = System.getProperty("stellarai.metadata.db");
            DesktopAppService service = null;
            MainShellController shell = null;
            try {
                Path home = temp.resolve("home-empty");
                Path db = temp.resolve("meta-empty.db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
                service = new DesktopAppService(new DesktopSession(), store);

                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                shell = loader.getController();
                shell.init(service);
                shell.openRoute(WorkspaceRoute.PROJECTS_HOME);

                DashboardController dashboard =
                        (DashboardController) shell.controllersForTest().get(NavigationPage.PROJECTS_HOME);
                dashboard.refresh();

                assertEquals(5, dashboard.kpiRowForTest().getChildren().size());
                assertEquals("No project", dashboard.journeyStatusBadgeForTest().getText());
                assertTrue(dashboard.resumeButtonForTest().isDisable());
                assertTrue(dashboard.recentProjectsTableForTest().getChildren().isEmpty()
                        || !dashboard.recentProjectsTableForTest().isVisible());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (shell != null) shell.dispose();
                if (service != null) service.shutdown();
                if (previousHome == null) System.clearProperty("stellarai.home");
                else System.setProperty("stellarai.home", previousHome);
                if (previousDb == null) System.clearProperty("stellarai.metadata.db");
                else System.setProperty("stellarai.metadata.db", previousDb);
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static int countNodesInRow(GridPane table, int row) {
        int count = 0;
        for (Node n : table.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            if (r != null && r == row) {
                count++;
            }
        }
        return count;
    }

    private static int dataRowCount(GridPane table) {
        int max = -1;
        for (Node n : table.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            if (r != null) {
                max = Math.max(max, r);
            }
        }
        return Math.max(0, max);
    }

    private static boolean identityRowsAreClickable(GridPane table) {
        int clickable = 0;
        for (Node n : table.getChildren()) {
            Integer col = GridPane.getColumnIndex(n);
            Integer row = GridPane.getRowIndex(n);
            if (col != null && row != null && col == RecentProjectsTableLayout.COL_PROJECT && row > 0) {
                if (n.getOnMouseClicked() == null || n.getCursor() != javafx.scene.Cursor.HAND) {
                    return false;
                }
                clickable++;
            }
        }
        return clickable >= 2;
    }

    private static List<String> rowTitles(GridPane table) {
        List<String> titles = new java.util.ArrayList<>();
        for (Node n : table.getChildren()) {
            Integer col = GridPane.getColumnIndex(n);
            Integer row = GridPane.getRowIndex(n);
            if (col != null && row != null && col == RecentProjectsTableLayout.COL_PROJECT && row > 0) {
                titles.add(extractTitle(n));
            }
        }
        return titles;
    }

    private static String extractTitle(Node node) {
        if (node instanceof HBox box && !box.getChildren().isEmpty()) {
            for (Node child : box.getChildren()) {
                if (child instanceof VBox text && !text.getChildren().isEmpty()
                        && text.getChildren().get(0) instanceof Label label) {
                    return label.getText() == null ? "" : label.getText();
                }
            }
        }
        return "";
    }

    private static List<Button> findButtons(Node root) {
        List<Button> out = new java.util.ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof Button b) {
                out.add(b);
            }
            if (n instanceof javafx.scene.Parent parent) {
                q.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return out;
    }
}
