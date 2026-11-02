package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shell-level V1 routing: creating and reopening a project must land on a meaningful workspace
 * without bouncing through Projects Home.
 */
class ShellProjectRoutingTest {

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
    }

    @Test
    @Timeout(60)
    void newProjectOpensSourcesWithoutProjectsHomeBounce() throws Exception {
        onFx("routing-new", (service, shell) -> {
            assertEquals(NavigationPage.HOME, shell.visiblePage());

            service.newProject("Durability Run");
            shell.onProjectCreated();

            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            assertEquals(NavigationPage.SOURCES, shell.visiblePage());
            assertNotEquals(NavigationPage.PROJECTS_HOME, shell.visiblePage());
            assertEquals(NavigationPage.SOURCES, service.currentPage());
            assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS),
                    "analysis pages must not be materialized for a brand-new project");
            assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));
        });
    }

    @Test
    @Timeout(60)
    void openSavedEmptyProjectResumesSourcesWithoutProjectsHomeBounce() throws Exception {
        onFx("routing-open", (service, shell) -> {
            service.newProject("Road Validation");
            String first = service.currentProjectId();
            service.newProject("Vehicle Durability Test");

            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
            assertEquals(NavigationPage.PROJECTS_HOME, shell.visiblePage());

            assertTrue(service.openSavedProject(first).opened());
            shell.onProjectHydrated();

            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            assertNotEquals(NavigationPage.PROJECTS_HOME, shell.visiblePage());
            assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS),
                    "reopening a project must not materialize the graph workspace");
        });
    }

    @Test
    @Timeout(60)
    void analysisNavigationIsUnavailableUntilAnalysisDataExists() throws Exception {
        onFx("routing-access", (service, shell) -> {
            service.newProject("Access Rules");
            shell.onProjectCreated();

            var permanent = shell.permanentNavButtonsForTest();
            assertEquals(5, permanent.size());
            assertTrue(permanent.get(0).isVisible(), "Sources must stay visible");
            assertFalse(permanent.get(0).isDisable(), "Sources is usable once a project is open");
            assertTrue(permanent.get(1).isVisible(), "Decode must stay visible during setup");
            assertTrue(permanent.get(1).isDisable(), "Decode is not usable without a usable source");
            assertTrue(permanent.get(2).isVisible(), "Signals stays visible as a disabled product-map entry");
            assertTrue(permanent.get(2).isDisable(), "Signals is not usable without analysis data");
            assertTrue(permanent.get(3).isVisible(), "Graphs stays visible as a disabled product-map entry");
            assertTrue(permanent.get(3).isDisable(), "Graphs is not usable without analysis data");
            assertTrue(permanent.get(4).isVisible(), "Investigations stays visible as a disabled product-map entry");
            assertTrue(permanent.get(4).isDisable(), "Investigations is not usable without analysis data");
        });
    }

    @Test
    @Timeout(60)
    void readySourceEnablesSidebarDecodeWithoutOpeningDecode() throws Exception {
        onFx("routing-decode-gate", (service, shell) -> {
            service.newProject("Decode Gate");
            shell.onProjectCreated();
            assertTrue(shell.decodeNavDisabledForTest(), "empty project cannot enter Decode");
            assertFalse(service.canContinueToDecode());

            Path path = temp.resolve("routing-decode-gate").resolve("run01.mf4");
            Files.createDirectories(path.getParent());
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

            assertTrue(service.canContinueToDecode());
            assertFalse(shell.decodeNavDisabledForTest(),
                    "sidebar Decode must enable from the same usable-source rule as Continue to Decode");
        });
    }

    @Test
    @Timeout(60)
    void processingHasNoPermanentNavigationEntry() throws Exception {
        onFx("routing-transient", (service, shell) -> {
            service.newProject("Transient Processing");
            shell.onProjectCreated();

            assertTrue(shell.permanentNavButtonsForTest().stream()
                    .noneMatch(b -> "Processing".equals(b.getText())
                            || "Review & Process".equals(b.getText())
                            || "Inventory".equals(b.getText())
                            || "DBC Mapping".equals(b.getText())));

            shell.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            assertEquals(NavigationPage.PROCESSING, shell.visiblePage());
            assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS, shell.visibleRoute());
            assertTrue(shell.permanentNavButtonsForTest().stream()
                    .noneMatch(b -> b.getStyleClass().contains("sidebar-item-selected")),
                    "a transient route must not select a permanent navigation entry");
        });
    }

    @Test
    @Timeout(60)
    void sourcesAndDecodeShareTheProjectFilesHost() throws Exception {
        onFx("routing-hosts", (service, shell) -> {
            service.newProject("Shared Host");
            shell.onProjectCreated();
            int afterSources = shell.loadedPageCountForTest();

            shell.openRoute(WorkspaceRoute.DECODE);
            assertEquals(NavigationPage.DECODE, shell.visiblePage());
            assertEquals(afterSources, shell.loadedPageCountForTest());
        });
    }

    private interface ShellScenario {
        void run(DesktopAppService service, MainShellController shell) throws Exception;
    }

    private void onFx(String slug, ShellScenario scenario) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            String previousDb = System.getProperty("stellarai.metadata.db");
            DesktopAppService service = null;
            MainShellController shell = null;
            try {
                Path home = temp.resolve(slug);
                Path db = temp.resolve(slug + ".db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                FXMLLoader loader = new FXMLLoader(
                        MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                shell = loader.getController();
                shell.init(service);
                scenario.run(service, shell);
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
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX scenario timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
