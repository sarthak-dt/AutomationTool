package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.StellarDesktopApp;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.home.WorkspaceHomeLayout;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeDashboardLayoutTest {

    @TempDir
    Path temp;

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
    @Timeout(180)
    void workspaceStaysUsableAtLaptopAndDesktopSizes() throws Exception {
        onFx(() -> {
            Path evidence = workspaceEvidenceDir();
            Files.createDirectories(evidence);

            assertLayout(1366, 768, evidence.resolve("window-1366x768.png"));
            assertLayout(1536, 864, evidence.resolve("window-1536x864.png"));
            assertLayout(1600, 900, evidence.resolve("window-1600x900.png"));
            assertLayout(1920, 1080, evidence.resolve("window-1920x1080.png"));
            assertLiveResize(evidence.resolve("live-resize-1366-after-1920.png"));
        });
    }

    private static Path workspaceEvidenceDir() {
        Path cwd = Path.of(System.getProperty("user.dir", "")).toAbsolutePath();
        if ("stellar-desktop".equals(cwd.getFileName().toString()) && cwd.getParent() != null) {
            return cwd.getParent().resolve("qa").resolve("workspace-layout");
        }
        return cwd.resolve("qa").resolve("workspace-layout");
    }

    private void assertLiveResize(Path png) throws Exception {
        Path home = temp.resolve("home-resize");
        Path db = temp.resolve("meta-resize.db");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(db));
        seedProjects(service);
        Stage stage = new Stage();
        try {
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            Parent root = loader.load();
            MainShellController shell = loader.getController();
            shell.init(service);
            Scene scene = new Scene(root, 1920, 1080);
            scene.getStylesheets().add(StellarDesktopApp.class.getResource("/css/stellar-desktop.css").toExternalForm());
            stage.setScene(scene);
            stage.setWidth(1920);
            stage.setHeight(1080);
            stage.show();
            root.applyCss();
            root.layout();

            HomeDashboardController dashboard =
                    (HomeDashboardController) shell.controllersForTest().get(NavigationPage.HOME);
            dashboard.refresh();
            root.layout();
            Node page = shell.pageNodeForTest(NavigationPage.HOME);
            assertFalse(dashboard.compactLayoutForTest()
                            && dashboard.pageRootForTest().getWidth() >= WorkspaceHomeLayout.STACK_BELOW,
                    "wide start should not stay compact");

            stage.setWidth(1366);
            stage.setHeight(768);
            root.applyCss();
            root.layout();
            dashboard.refresh();
            root.layout();
            assertInViewport(namedButton(page, "New Project"), scene, "New Project after shrink");
            assertInViewport(labeled(page, "CAN Frames"), scene, "KPI after shrink");
            assertHorizontallyInBounds(labeled(page, "Get Started"), scene, "Get Started after shrink");
            assertProjectsGrow(page, 768);
            assertStatusStrip(scene);
            assertStatusColumnReadable(page);

            stage.setWidth(1920);
            stage.setHeight(1080);
            root.applyCss();
            root.layout();
            dashboard.refresh();
            root.layout();
            assertInViewport(namedButton(page, "New Project"), scene, "New Project after restore");
            assertProjectsGrow(page, 1080);
            assertStatusStrip(scene);
            assertStatusColumnReadable(page);
            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png", png.toFile());
        } finally {
            stage.close();
            service.shutdown();
        }
    }

    private void assertLayout(int width, int height, Path png) throws Exception {
        Path home = temp.resolve("home-" + width);
        Path db = temp.resolve("meta-" + width + ".db");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(db));
        seedProjects(service);
        Stage stage = new Stage();
        try {
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            Parent root = loader.load();
            MainShellController shell = loader.getController();
            shell.init(service);

            Scene scene = new Scene(root, width, height);
            scene.getStylesheets().add(StellarDesktopApp.class.getResource("/css/stellar-desktop.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(width);
            stage.setMinHeight(height);
            stage.setWidth(width);
            stage.setHeight(height);
            stage.show();
            stage.toFront();
            root.applyCss();
            root.layout();

            HomeDashboardController dashboard =
                    (HomeDashboardController) shell.controllersForTest().get(NavigationPage.HOME);
            dashboard.refresh();
            root.applyCss();
            root.layout();
            root.applyCss();
            root.layout();

            Node page = shell.pageNodeForTest(NavigationPage.HOME);
            assertTrue(page instanceof ScrollPane);
            ScrollPane scroll = (ScrollPane) page;
            assertEquals("NEVER", scroll.getHbarPolicy().name());
            assertEquals("AS_NEEDED", scroll.getVbarPolicy().name());

            double pageWidth = dashboard.pageRootForTest().getWidth();
            if (pageWidth > 0 && pageWidth < WorkspaceHomeLayout.STACK_BELOW) {
                assertTrue(dashboard.compactLayoutForTest(), "compact class at " + width + " pageW=" + pageWidth);
            }
            if (pageWidth >= WorkspaceHomeLayout.STACK_BELOW) {
                assertFalse(dashboard.compactLayoutForTest(), "wide class at " + width + " pageW=" + pageWidth);
            }

            Button newProject = namedButton(page, "New Project");
            assertInViewport(newProject, scene, "New Project at " + width);
            assertInViewport(namedButton(page, "Open Existing Project"), scene, "Open Existing at " + width);
            assertInViewport(labeled(page, "Projects"), scene, "Projects KPI at " + width);
            assertInViewport(labeled(page, "CAN Frames"), scene, "CAN Frames KPI at " + width);
            assertInViewport(labeled(page, "Quick Actions"), scene, "Quick Actions at " + width);
            assertHorizontallyInBounds(labeled(page, "Get Started"), scene, "Get Started at " + width);
            assertHorizontallyInBounds(namedButton(page, "Project Sources"), scene, "Get Started card at " + width);
            assertEquals(1, countNamed(page, "New Project"));
            assertFullyVisible(labeled(page, "Quick Actions"), "Quick Actions heading");
            assertFullyVisible(labeled(page, "Recent Activity"), "Recent Activity heading");
            assertFullyVisible(actionLabel(page, "Add Measurement File"), "Add Measurement File");
            assertFullyVisible(actionLabel(page, "Open Existing Project"), "Open Existing Project");
            assertPanelArrangement(page, pageWidth);
            assertKpiSingleRow(page, pageWidth);
            assertProjectsGrow(page, height);
            assertEnabledIconIsVisible(newProject, "New Project");
            if (pageWidth > 0 && pageWidth < WorkspaceHomeLayout.STACK_BELOW) {
                Node projectsPanel = page.lookup(".project-list");
                assertTrue(projectsPanel instanceof Region, "projects panel missing");
                assertTrue(((Region) projectsPanel).getMaxHeight() > 400,
                        "projects panel is still height-capped and can paint over Quick Actions");
                scroll.setVvalue(1);
                root.applyCss();
                root.layout();
                assertInViewport(namedButton(page, "Project Sources"), scene,
                        "Get Started card after scroll at " + width);
                scroll.setVvalue(0);
                root.applyCss();
                root.layout();
            }
            assertTrue(RecentProjectsTableLayout.columnConstraints()
                    .get(RecentProjectsTableLayout.COL_STATUS).getMinWidth()
                    >= RecentProjectsTableLayout.STATUS_MIN_WIDTH);
            assertEquals(Priority.ALWAYS, RecentProjectsTableLayout.columnConstraints()
                    .get(RecentProjectsTableLayout.COL_PROJECT).getHgrow());
            assertEquals(Priority.NEVER, RecentProjectsTableLayout.columnConstraints()
                    .get(RecentProjectsTableLayout.COL_STATUS).getHgrow());
            assertStatusStrip(scene);
            assertStatusColumnReadable(page);

            ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png", png.toFile());
        } finally {
            stage.close();
            service.shutdown();
        }
    }

    private static void assertKpiSingleRow(Node page, double pageWidth) {
        if (pageWidth < 980) {
            return;
        }
        Bounds projects = sceneBounds(metricLabel(page, "Projects"));
        Bounds frames = sceneBounds(metricLabel(page, "CAN Frames"));
        assertTrue(Math.abs(projects.getMinY() - frames.getMinY()) < 14,
                "CAN Frames left the KPI row " + frames + " projects=" + projects);
        assertTrue(frames.getMinX() > projects.getMaxX(),
                "CAN Frames should sit on the same row");
    }

    private static void seedProjects(DesktopAppService service) {
        for (int i = 1; i <= 8; i++) {
            service.newProject("Layout Project " + i);
        }
    }

    private static void assertProjectsGrow(Node page, int windowHeight) {
        ScrollPane table = projectTable(page);
        assertTrue(table != null, "project table scroll missing");
        double minRows = RecentProjectsTableLayout.HEADER_HEIGHT
                + WorkspaceHomeLayout.PROJECT_ROWS_MIN * RecentProjectsTableLayout.ROW_HEIGHT;
        assertTrue(table.getMinHeight() + 1 >= minRows,
                "project table min height is still a one-row cap: " + table.getMinHeight());
        Node panel = styled(page, "project-list");
        assertTrue(panel instanceof Region);
        assertTrue(((Region) panel).getHeight() + 1 >= minRows,
                "projects panel did not grow at " + windowHeight + ": " + ((Region) panel).getHeight());
        int visible = visibleProjectRows(page, table);
        int expected = windowHeight >= 700 ? 4 : 2;
        assertTrue(visible >= expected,
                "only " + visible + " project rows visible at " + windowHeight
                        + " tableH=" + table.getHeight());
    }

    private static ScrollPane projectTable(Node root) {
        List<Node> found = new ArrayList<>();
        walkAll(root, found);
        for (Node node : found) {
            if (node instanceof ScrollPane scroll && scroll.getStyleClass().contains("project-table-scroll")) {
                return scroll;
            }
        }
        return null;
    }

    private static int visibleProjectRows(Node page, ScrollPane table) {
        Bounds view = table.localToScene(table.getBoundsInLocal());
        List<Node> found = new ArrayList<>();
        walkAll(page, found);
        int visible = 0;
        for (Node node : found) {
            if (!node.getStyleClass().contains("project-row") || !node.isVisible()) {
                continue;
            }
            Bounds row = node.localToScene(node.getBoundsInLocal());
            if (row.getHeight() < 8) {
                continue;
            }
            double overlap = Math.min(row.getMaxY(), view.getMaxY()) - Math.max(row.getMinY(), view.getMinY());
            if (overlap > row.getHeight() * 0.6) {
                visible++;
            }
        }
        return visible;
    }

    private static void walkAll(Node node, List<Node> out) {
        IdentityHashMap<Node, Boolean> seen = new IdentityHashMap<>();
        walkAll(node, out, seen);
    }

    private static void walkAll(Node node, List<Node> out, IdentityHashMap<Node, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return;
        }
        out.add(node);
        if (node instanceof ScrollPane scroll) {
            walkAll(scroll.getContent(), out, seen);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkAll(child, out, seen);
            }
        }
    }

    private static void assertEnabledIconIsVisible(Button button, String label) {
        Color stroke = firstStroke(button.getGraphic());
        assertTrue(stroke != null, label + " icon missing");
        assertTrue(stroke.getOpacity() > 0.4, label + " icon is transparent " + stroke);
        assertFalse(stroke.getRed() > 0.9 && stroke.getGreen() > 0.9 && stroke.getBlue() > 0.9,
                label + " icon is white " + stroke);
    }

    private static Color firstStroke(Node node) {
        if (node instanceof Shape shape && shape.getStroke() instanceof Color color) {
            return color;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Color found = firstStroke(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Node metricLabel(Node root, String text) {
        List<Node> found = new ArrayList<>();
        walkLabeled(root, text, found);
        for (Node node : found) {
            if (node.getStyleClass().contains("metric-label")) {
                return node;
            }
        }
        throw new AssertionError("Missing KPI label: " + text);
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private static void assertFullyVisible(Node node, String label) {
        assertTrue(node instanceof Labeled, label + " is not text");
        Labeled labeled = (Labeled) node;
        double pref = labeled.prefWidth(-1);
        assertTrue(labeled.getWidth() + 1.5 >= pref,
                label + " truncated width=" + labeled.getWidth() + " pref=" + pref
                        + " text=" + labeled.getText());
    }

    private static Labeled actionLabel(Node root, String text) {
        List<Node> found = new ArrayList<>();
        walkLabeled(root, text, found);
        for (Node node : found) {
            if (node instanceof Labeled labeled && text.equals(labeled.getText())) {
                return labeled;
            }
        }
        throw new AssertionError("Missing action label: " + text);
    }

    private static void assertPanelArrangement(Node page, double pageWidth) {
        Node projects = sideTitle(page, "Projects");
        Node quick = sideTitle(page, "Quick Actions");
        Node recent = sideTitle(page, "Recent Activity");
        Bounds projectBounds = projects.localToScene(projects.getBoundsInLocal());
        Bounds quickBounds = quick.localToScene(quick.getBoundsInLocal());
        Bounds recentBounds = recent.localToScene(recent.getBoundsInLocal());
        if (pageWidth >= WorkspaceHomeLayout.STACK_BELOW) {
            Node rail = styled(page, "workspace-side-rail");
            assertTrue(rail.getBoundsInLocal().getWidth() + 1 >= WorkspaceHomeLayout.RAIL_MIN,
                    "side rail collapsed " + rail.getBoundsInLocal());
            assertTrue(quickBounds.getMinX() > projectBounds.getMaxX() - 8,
                    "Quick Actions should stay beside Projects " + quickBounds + " " + projectBounds);
        } else if (pageWidth >= WorkspaceHomeLayout.STACK_LOWER_BELOW) {
            assertTrue(quickBounds.getMinY() >= projectBounds.getMaxY() - 8,
                    "Quick Actions should move below Projects " + quickBounds + " projects=" + projectBounds);
            assertTrue(Math.abs(quickBounds.getMinY() - recentBounds.getMinY()) < 30,
                    "Recent Activity should share the lower row " + recentBounds + " quick=" + quickBounds);
            assertTrue(recentBounds.getMinX() > quickBounds.getMinX() + 40,
                    "Recent Activity should sit beside Quick Actions");
        }
    }

    private static Node sideTitle(Node root, String text) {
        List<Node> found = new ArrayList<>();
        walkLabeled(root, text, found);
        for (Node node : found) {
            if (node.getStyleClass().contains("side-panel-title")) {
                return node;
            }
        }
        if (found.isEmpty()) {
            throw new AssertionError("Missing title: " + text);
        }
        return found.getFirst();
    }

    private static Node styled(Node root, String styleClass) {
        List<Node> found = new ArrayList<>();
        walkStyled(root, styleClass, found);
        if (found.isEmpty()) {
            throw new AssertionError("Missing style " + styleClass);
        }
        return found.getFirst();
    }

    private static void walkStyled(Node node, String styleClass, List<Node> out) {
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            walkStyled(scroll.getContent(), styleClass, out);
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

    private static void assertStatusStrip(Scene scene) {
        Node bar = scene.lookup(".app-status-bar");
        assertTrue(bar instanceof Region, "status strip missing");
        Region strip = (Region) bar;
        Bounds bounds = strip.localToScene(strip.getBoundsInLocal());
        assertTrue(bounds.getMinY() >= -0.5, "status strip above the scene " + bounds);
        assertTrue(bounds.getMaxY() <= scene.getHeight() + 0.5,
                "status strip outside the client " + bounds + " scene=" + scene.getHeight());
        assertTrue(strip.getHeight() >= 24 && strip.getHeight() <= 32,
                "status strip height " + strip.getHeight());
        Node text = null;
        if (bar instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                    text = child;
                    break;
                }
            }
        }
        assertTrue(text instanceof Labeled, "status strip text missing");
        Bounds textBounds = text.localToScene(text.getBoundsInLocal());
        assertTrue(textBounds.getMinY() >= bounds.getMinY() - 0.5
                        && textBounds.getMaxY() <= bounds.getMaxY() + 0.5,
                "status text clipped by the strip " + textBounds + " bar=" + bounds);
        double above = textBounds.getMinY() - bounds.getMinY();
        double below = bounds.getMaxY() - textBounds.getMaxY();
        assertTrue(Math.abs(above - below) <= 4,
                "status text not vertically centered above=" + above + " below=" + below);
        assertTrue(((Labeled) text).getWidth() + 1 >= ((Labeled) text).prefWidth(-1),
                "status text truncated");
    }

    private static void assertStatusColumnReadable(Node page) {
        Node badge = firstStyled(page, "status-badge");
        assertTrue(badge instanceof Region, "status badge missing");
        Region region = (Region) badge;
        assertTrue(region.getWidth() + 0.5 >= RecentProjectsTableLayout.STATUS_MIN_WIDTH,
                "status column too tight width=" + region.getWidth());
        assertTrue(badge instanceof Labeled);
        Labeled labeled = (Labeled) badge;
        assertTrue(region.getWidth() + 1.5 >= labeled.prefWidth(-1),
                "status text clipped width=" + region.getWidth() + " pref=" + labeled.prefWidth(-1)
                        + " text=" + labeled.getText());
    }

    private static Node firstStyled(Node node, String styleClass) {
        if (node == null) {
            return null;
        }
        if (node.getStyleClass().contains(styleClass)) {
            return node;
        }
        if (node instanceof ScrollPane scroll) {
            Node found = firstStyled(scroll.getContent(), styleClass);
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstStyled(child, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertInViewport(Node node, Scene scene, String label) {
        assertHorizontallyInBounds(node, scene, label);
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinY() >= -2, label + " clipped top " + bounds);
        assertTrue(bounds.getMaxY() <= scene.getHeight() + 8, label + " clipped below viewport " + bounds
                + " scene=" + scene.getHeight());
    }

    private static void assertHorizontallyInBounds(Node node, Scene scene, String label) {
        assertTrue(node.isVisible(), label + " hidden");
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= -2, label + " clipped left " + bounds);
        assertTrue(bounds.getMaxX() <= scene.getWidth() + 2, label + " clipped right " + bounds
                + " scene=" + scene.getWidth());
        assertTrue(bounds.getWidth() > 8 && bounds.getHeight() > 8, label + " collapsed " + bounds);
    }

    private static Button namedButton(Node root, String name) {
        for (Button button : buttons(root)) {
            if (name.equals(button.getText()) || labels(button).contains(name)) {
                return button;
            }
        }
        throw new AssertionError("Missing button: " + name);
    }

    private static Node labeled(Node root, String name) {
        List<Node> found = new ArrayList<>();
        walkLabeled(root, name, found);
        if (found.isEmpty()) {
            throw new AssertionError("Missing label: " + name);
        }
        return found.getFirst();
    }

    private static void walkLabeled(Node node, String name, List<Node> out) {
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            walkLabeled(scroll.getContent(), name, out);
            return;
        }
        if (node instanceof Labeled labeled) {
            if (name.equals(labeled.getText())) {
                out.add(labeled);
            }
            if (labeled.getGraphic() != null) {
                walkLabeled(labeled.getGraphic(), name, out);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walkLabeled(child, name, out);
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

    private static List<String> labels(Node node) {
        List<String> out = new ArrayList<>();
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            out.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                out.addAll(labels(child));
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
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            walkButtons(scroll.getContent(), out);
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

    private void onFx(FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            try {
                work.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (previousHome == null) {
                    System.clearProperty("stellarai.home");
                } else {
                    System.setProperty("stellarai.home", previousHome);
                }
                done.countDown();
            }
        });
        assertTrue(done.await(80, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxWork {
        void run() throws Exception;
    }
}
