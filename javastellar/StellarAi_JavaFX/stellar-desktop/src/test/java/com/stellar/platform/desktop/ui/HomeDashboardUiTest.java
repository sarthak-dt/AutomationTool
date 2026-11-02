package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.home.WorkspaceUiPreferencesStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeDashboardUiTest {

    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        System.setProperty("stellarai.dashboard.sync", "true");
    }

    @Test
    @Timeout(45)
    void workspaceUsesApprovedLayoutAndAutoOpensLatestUpdatedProject() throws Exception {
        onFx(home -> {
            DesktopAppService service = home.service;
            MainShellController shell = home.shell;
            service.newProject("BMW_M3_NVH_Validation_2024");
            String projectId = service.currentProjectId();
            service.closeProject();

            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            Parent root = loader.load();
            home.attach(loader.getController(), root);
            shell = home.shell;
            shell.init(service);

            assertEquals(WorkspaceRoute.HOME, shell.visibleRoute());
            assertTrue(service.isDurableNamedProject());
            assertEquals(projectId, service.currentProjectId());
            assertTrue(shell.projectNameChromeForTest().contains("BMW_M3_NVH_Validation_2024"));
            assertFalse(shell.projectNameChromeForTest().contains("No active project"));

            Object controller = shell.controllersForTest().get(NavigationPage.HOME);
            assertTrue(controller instanceof HomeDashboardController);
            HomeDashboardController dashboard = (HomeDashboardController) controller;
            dashboard.refresh();

            Node page = shell.pageNodeForTest(NavigationPage.HOME);
            List<String> labels = labels(page);
            assertFalse(labels.contains("Welcome back"));
            assertFalse(labels.contains("No active project"));
            assertFalse(labels.contains("+ New Project"));
            assertEquals(1, countNamed(page, "New Project"));
            assertEquals(1, countNamed(page, "Open Existing Project"));
            assertTrue(labels.contains("Projects"));
            assertTrue(labels.contains("Measurement Files"));
            assertTrue(labels.contains("Signals"));
            assertTrue(labels.contains("CAN Frames"));
            assertTrue(labels.contains("Processing Success"));
            assertTrue(labels.contains("Recent Projects"));
            assertTrue(labels.contains("All Projects"));
            assertTrue(labels.contains("Favorites"));
            assertTrue(labels.contains("Quick Actions"));
            assertTrue(labels.contains("Get Started"));
            assertTrue(labels.contains("Recent Activity"));
            assertTrue(labels.contains("Project Sources"));
            assertTrue(labels.contains("Decode & Process"));
            assertTrue(labels.contains("Explore Signals"));
            assertTrue(labels.contains("Graphs & Analysis"));
            assertTrue(labels.contains("Add Measurement File"));
            assertTrue(labels.contains("Import DBC File"));
            assertTrue(labels.contains("Open Graphs"));
            assertTrue(labels.stream().anyMatch(t -> t.contains("BMW_M3_NVH_Validation_2024")));
            assertEquals(1, countStyle(page, "project-row-active-indicator"),
                    "active project should have one left indicator");
            assertTrue(countStyle(page, "project-row-active") >= 1);
            assertTrue(labels.contains("Active"));
            assertTrue(labels.contains("Active workspace"));
            for (Node badge : styled(page, "status-badge")) {
                assertFalse(badge.getStyleClass().contains("project-row-active"),
                        "status badge must stay independent of the active row");
            }
            for (Button star : favoriteButtons(page)) {
                assertFalse(star.getStyleClass().contains("project-row-active"));
                assertFalse(star.getStyleClass().contains("project-row-active-indicator"));
            }

            assertFalse(namedButton(page, "New Project").isDisable());
            assertFalse(namedButton(page, "Open Existing Project").isDisable());
            assertFalse(namedButton(page, "Add Measurement File").isDisable());
            assertFalse(namedButton(page, "Import DBC File").isDisable());
            assertTrue(namedButton(page, "Explore Signals").isDisable());
            assertTrue(namedButton(page, "Open Graphs").isDisable());
            assertTrue(namedButton(page, "Project Sources").getStyleClass().contains("workflow-card"));
            assertFalse(namedButton(page, "Project Sources").isDisable());
            assertTrue(namedButton(page, "Decode & Process").isDisable());
            assertTrue(namedButton(page, "Explore Signals").isDisable());

            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
            assertEquals(WorkspaceRoute.PROJECTS_HOME, shell.visibleRoute());
            shell.openRoute(WorkspaceRoute.HOME);
            assertEquals(WorkspaceRoute.HOME, shell.visibleRoute());
            shell.openRoute(WorkspaceRoute.SOURCES);
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(45)
    void emptyWorkspaceKeepsSameLayoutAndDisablesProjectDependentActions() throws Exception {
        onFx(home -> {
            DesktopAppService service = home.service;
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            loader.load();
            home.attach(loader.getController(), null);
            home.shell.init(service);

            assertFalse(service.isDurableNamedProject());
            assertEquals("No active project", home.shell.projectNameChromeForTest());
            assertEquals(WorkspaceRoute.HOME, home.shell.visibleRoute());

            Node page = home.shell.pageNodeForTest(NavigationPage.HOME);
            List<String> labels = labels(page);
            assertTrue(labels.contains("Quick Actions"));
            assertTrue(labels.contains("Get Started"));
            assertTrue(labels.contains("Recent Activity"));
            assertTrue(labels.contains("No recent activity yet."));
            assertTrue(labels.contains("No saved projects yet."));
            assertFalse(labels.contains("No active project"));
            assertEquals(1, countNamed(page, "New Project"));
            assertEquals(1, countNamed(page, "Open Existing Project"));
            assertFalse(namedButton(page, "New Project").isDisable());
            assertFalse(namedButton(page, "Open Existing Project").isDisable());
            assertTrue(namedButton(page, "Add Measurement File").isDisable());
            assertTrue(namedButton(page, "Import DBC File").isDisable());
            assertTrue(namedButton(page, "Explore Signals").isDisable());
            assertTrue(namedButton(page, "Open Graphs").isDisable());
            assertTrue(namedButton(page, "Project Sources").isDisable());
            assertTrue(namedButton(page, "Decode & Process").isDisable());
            assertTrue(namedButton(page, "Graphs & Analysis").isDisable());
            assertEquals("0 saved projects", home.shell.statusReadinessForTest());
            assertEquals("No active project", home.shell.statusProjectForTest());
            assertEquals("No active project", home.shell.statusWorkflowForTest());
        });
    }

    @Test
    @Timeout(60)
    void mostRecentlyUpdatedProjectBecomesActiveAndFavouritesPersist() throws Exception {
        onFx(home -> {
            DesktopAppService service = home.service;
            service.newProject("Lavasa");
            String lavasaId = service.currentProjectId();
            service.newProject("sample222");
            String sampleId = service.currentProjectId();
            assertEquals(sampleId, service.mostRecentlyUpdatedProjectId().orElseThrow());
            service.closeProject();

            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            loader.load();
            home.attach(loader.getController(), null);
            home.shell.init(service);

            assertEquals(sampleId, service.currentProjectId());
            assertTrue(home.shell.projectNameChromeForTest().contains("sample222"));
            assertFalse(home.shell.projectNameChromeForTest().contains("No active project"));

            Node page = home.shell.pageNodeForTest(NavigationPage.HOME);
            HomeDashboardController dashboard =
                    (HomeDashboardController) home.shell.controllersForTest().get(NavigationPage.HOME);
            dashboard.refresh();

            Button star = favoriteButtons(page).getFirst();
            star.fire();
            assertEquals(sampleId, service.currentProjectId(), "star must not open a project");

            new WorkspaceUiPreferencesStore().toggleFavorite(lavasaId);
            assertTrue(new WorkspaceUiPreferencesStore().isFavorite(lavasaId));

            home.shell.dispose();
            FXMLLoader restarted = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            restarted.load();
            home.attach(restarted.getController(), null);
            home.shell.init(service);
            assertEquals(sampleId, service.currentProjectId());
            assertTrue(new WorkspaceUiPreferencesStore().isFavorite(lavasaId));

            HomeDashboardController afterRestart =
                    (HomeDashboardController) home.shell.controllersForTest().get(NavigationPage.HOME);
            afterRestart.refresh();
            ToggleButton favorites = toggle(home.shell.pageNodeForTest(NavigationPage.HOME), "Favorites");
            favorites.fire();
            List<String> favoriteLabels = labels(home.shell.pageNodeForTest(NavigationPage.HOME));
            assertTrue(favoriteLabels.stream().anyMatch(t -> t.contains("Lavasa")));

            afterRestart.applySearch("zzzz-does-not-exist");
            List<String> noMatch = labels(home.shell.pageNodeForTest(NavigationPage.HOME));
            assertTrue(noMatch.contains("No projects match your search."), String.valueOf(noMatch));
            assertFalse(noMatch.contains("No saved projects yet."));

            home.shell.requestOpenSavedProject(lavasaId);
            assertEquals(lavasaId, service.currentProjectId());
            assertTrue(home.shell.projectNameChromeForTest().contains("Lavasa"));
        });
    }

    private void onFx(FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            String previousDb = System.getProperty("stellarai.metadata.db");
            HomeFx ctx = null;
            try {
                Path home = temp.resolve("home-" + System.nanoTime());
                Path db = temp.resolve("meta-" + System.nanoTime() + ".db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                ctx = new HomeFx(new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db)), null);
                work.run(ctx);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (ctx != null && ctx.shell != null) {
                    ctx.shell.dispose();
                }
                if (ctx != null && ctx.service != null) {
                    ctx.service.shutdown();
                }
                if (previousHome == null) {
                    System.clearProperty("stellarai.home");
                } else {
                    System.setProperty("stellarai.home", previousHome);
                }
                if (previousDb == null) {
                    System.clearProperty("stellarai.metadata.db");
                } else {
                    System.setProperty("stellarai.metadata.db", previousDb);
                }
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxWork {
        void run(HomeFx home) throws Exception;
    }

    private static final class HomeFx {
        private final DesktopAppService service;
        private MainShellController shell;

        private HomeFx(DesktopAppService service, MainShellController shell) {
            this.service = service;
            this.shell = shell;
        }

        private void attach(MainShellController controller, Parent ignored) {
            this.shell = controller;
        }
    }

    private static List<String> labels(Node root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Node node, List<String> out) {
        if (node != null && !node.isVisible()) {
            return;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                walk(scroll.getContent(), out);
            }
            return;
        }
        if (node instanceof javafx.scene.control.Labeled labeled) {
            if (labeled.getText() != null && !labeled.getText().isBlank()) {
                out.add(labeled.getText());
            }
            if (labeled.getGraphic() != null) {
                walk(labeled.getGraphic(), out);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, out);
            }
        }
    }

    private static int countNamed(Node root, String name) {
        int count = 0;
        for (Button button : buttons(root)) {
            if (name.equals(button.getText()) || labels(button).contains(name)) {
                count++;
            }
        }
        return count;
    }

    private static Button namedButton(Node root, String name) {
        for (Button button : buttons(root)) {
            if (name.equals(button.getText()) || labels(button).contains(name)) {
                return button;
            }
        }
        throw new AssertionError("Missing button: " + name);
    }

    private static ToggleButton toggle(Node root, String name) {
        List<ToggleButton> found = new ArrayList<>();
        walkToggles(root, found);
        for (ToggleButton button : found) {
            if (name.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("Missing toggle: " + name);
    }

    private static void walkToggles(Node node, List<ToggleButton> out) {
        if (node != null && !node.isVisible()) {
            return;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                walkToggles(scroll.getContent(), out);
            }
            return;
        }
        if (node instanceof ToggleButton button) {
            out.add(button);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkToggles(child, out);
            }
        }
    }

    private static int countStyle(Node root, String styleClass) {
        return styled(root, styleClass).size();
    }

    private static List<Node> styled(Node root, String styleClass) {
        List<Node> out = new ArrayList<>();
        walkStyled(root, styleClass, out);
        return out;
    }

    private static void walkStyled(Node node, String styleClass, List<Node> out) {
        if (node == null || !node.isVisible()) {
            return;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                walkStyled(scroll.getContent(), styleClass, out);
            }
            return;
        }
        if (node.getStyleClass().contains(styleClass)) {
            out.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkStyled(child, styleClass, out);
            }
        }
    }

    private static List<Button> favoriteButtons(Node root) {
        List<Button> out = new ArrayList<>();
        for (Button button : buttons(root)) {
            if (button.getStyleClass().contains("favorite-button")) {
                out.add(button);
            }
        }
        return out;
    }

    private static List<Button> buttons(Node root) {
        List<Button> out = new ArrayList<>();
        walkButtons(root, out);
        return out;
    }

    private static void walkButtons(Node node, List<Button> out) {
        if (node != null && !node.isVisible()) {
            return;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                walkButtons(scroll.getContent(), out);
            }
            return;
        }
        if (node instanceof Button button) {
            out.add(button);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkButtons(child, out);
            }
        }
    }
}
