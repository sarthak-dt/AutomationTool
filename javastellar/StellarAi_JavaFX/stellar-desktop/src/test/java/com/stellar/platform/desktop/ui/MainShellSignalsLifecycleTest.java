package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-RC-08: leaving Signals/Graphs must drop the previous JavaFX page instance.
 */
class MainShellSignalsLifecycleTest {

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
    void leavingSignalsDropsCachedPaneAndController() throws Exception {
        onFx((service, shell) -> {
            shell.show(NavigationPage.SIGNALS);
            assertTrue(shell.pageLoadedForTest(NavigationPage.SIGNALS));
            Object first = shell.controllerForTest(NavigationPage.SIGNALS);
            assertTrue(first instanceof SignalsController);

            shell.show(NavigationPage.PROJECTS_HOME);
            assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS),
                    "Signals FXML must be evicted after navigating to Projects");
            assertNull(shell.controllerForTest(NavigationPage.SIGNALS));
            assertNull(shell.pageNodeForTest(NavigationPage.SIGNALS));
            assertTrue(((SignalsController) first).disposedForTest());
            assertEquals(0, service.signalsModel().favoritesListenerCountForTest());
            assertEquals(0, service.signalsModel().recentsListenerCountForTest());
        });
    }

    @Test
    @Timeout(40)
    void repeatedSignalsNavigationDoesNotAccumulatePageInstances() throws Exception {
        onFx((service, shell) -> {
            List<Object> instances = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                shell.show(NavigationPage.SIGNALS);
                Object current = shell.controllerForTest(NavigationPage.SIGNALS);
                assertTrue(current instanceof SignalsController);
                if (!instances.isEmpty()) {
                    assertNotSame(instances.getLast(), current,
                            "each Signals visit must create a new controller");
                }
                instances.add(current);
                shell.show(NavigationPage.PROJECTS_HOME);
                assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertEquals(2, shell.loadedPageCountForTest(),
                        "Home + Projects should remain cached after leaving Signals");
            }
            for (Object instance : instances) {
                assertTrue(((SignalsController) instance).disposedForTest());
            }
        });
    }

    @Test
    @Timeout(40)
    void leavingGraphsDropsGraphControllerAfterPersistingLightweightState() throws Exception {
        onFx((service, shell) -> {
            SignalCatalogKey key = new SignalCatalogKey(
                    "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x100, 1, "run.mf4", "veh.dbc");
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
            store.accept(List.of(new CanonicalSignal(
                    "VehicleSpeed", "Speed", 1_000L, 0x100, 1, 42.0, 42L, "km/h", null,
                    SourceReference.ofLine(1))));
            service.signalsModel().replaceCatalog(store);
            service.signalsModel().addToWorkspace(List.of(key));

            shell.show(NavigationPage.GRAPHS);
            assertTrue(shell.pageLoadedForTest(NavigationPage.GRAPHS));
            Object first = shell.controllerForTest(NavigationPage.GRAPHS);
            assertTrue(first instanceof GraphController);
            GraphInvestigationState inv = service.graphInvestigation();
            inv.setLayoutMode(GraphInvestigationState.OVERLAY);
            inv.setViewport(2_000L, 8_000L);
            inv.setNotes("keep after recreate");

            shell.show(NavigationPage.PROJECTS_HOME);
            assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));
            assertEquals(List.of(key), service.signalsModel().workspaceKeys());
            assertEquals(GraphInvestigationState.OVERLAY, service.graphInvestigation().layoutMode());
            assertEquals(2_000L, service.graphInvestigation().viewportStart());
            assertEquals(8_000L, service.graphInvestigation().viewportEnd());
            assertEquals("keep after recreate", service.graphInvestigation().notes());

            shell.show(NavigationPage.GRAPHS);
            assertNotSame(first, shell.controllerForTest(NavigationPage.GRAPHS));
            assertEquals(List.of(key), service.signalsModel().workspaceKeys());
        });
    }

    @Test
    @Timeout(60)
    void projectSwitchDoesNotRetainPreviousProjectSignalsUi() throws Exception {
        onFx((service, shell) -> {
            service.newProject("Project A");
            shell.onProjectCreated();
            shell.show(NavigationPage.SIGNALS);
            SignalsController projectA = (SignalsController) shell.controllerForTest(NavigationPage.SIGNALS);

            service.newProject("Project B");
            shell.onProjectCreated();
            assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS),
                    "creating a new project must evict the previous Signals tree");
            assertTrue(projectA.disposedForTest());

            shell.show(NavigationPage.SIGNALS);
            SignalsController projectB = (SignalsController) shell.controllerForTest(NavigationPage.SIGNALS);
            assertNotSame(projectA, projectB);

            shell.show(NavigationPage.PROJECTS_HOME);
            assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
        });
    }

    @Test
    @Timeout(40)
    void disposedSignalsWorkspaceCanBecomeUnreachable() throws Exception {
        AtomicReference<WeakReference<SignalsController>> controllerRef = new AtomicReference<>();
        AtomicReference<WeakReference<Parent>> rootRef = new AtomicReference<>();
        AtomicReference<DesktopAppService> serviceRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                DesktopAppService service = new DesktopAppService(new DesktopSession());
                serviceRef.set(service);
                FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
                Parent root = loader.load();
                SignalsController controller = loader.getController();
                controller.setAppService(service, null);
                controller.refresh();
                controller.dispose();
                controllerRef.set(new WeakReference<>(controller));
                rootRef.set(new WeakReference<>(root));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals(0, serviceRef.get().signalsModel().favoritesListenerCountForTest());
        assertEquals(0, serviceRef.get().signalsModel().recentsListenerCountForTest());
        serviceRef.get().shutdown();
        serviceRef.set(null);
        assertTrue(awaitCleared(controllerRef.get()), "disposed Signals controller must be collectable");
        assertTrue(awaitCleared(rootRef.get()), "disposed Signals FXML root must be collectable");
    }

    private void onFx(ShellScenario scenario) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            String previousHome = System.getProperty("stellarai.home");
            String previousDb = System.getProperty("stellarai.metadata.db");
            DesktopAppService service = null;
            MainShellController shell = null;
            try {
                Path home = temp.resolve("signals-lifecycle");
                Path db = temp.resolve("signals-lifecycle.db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                shell = loader.getController();
                shell.init(service);
                scenario.run(service, shell);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (shell != null) {
                    shell.dispose();
                }
                if (service != null) {
                    service.shutdown();
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
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX scenario timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static boolean awaitCleared(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            System.gc();
            if (ref.get() == null) {
                return true;
            }
            Thread.sleep(50);
        }
        return ref.get() == null;
    }

    @FunctionalInterface
    private interface ShellScenario {
        void run(DesktopAppService service, MainShellController shell) throws Exception;
    }
}
