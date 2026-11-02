package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.StellarDesktopApp;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.desktop.ui.signals.SignalInspectorPane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-RC-08: Scene/CSS/skin attached Signals must become unreachable after Projects.
 * Isolated from other lifecycle tests so a live Stage cannot stall the shared FX thread.
 */
class MainShellSignalsSceneLifecycleTest {

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
    @Timeout(90)
    void sceneAttachedSignalsTreeBecomesUnreachableAfterProjects() throws Exception {
        AtomicReference<WeakReference<SignalsController>> controllerRef = new AtomicReference<>();
        AtomicReference<WeakReference<Parent>> rootRef = new AtomicReference<>();
        AtomicReference<WeakReference<SignalBrowserPane>> browserRef = new AtomicReference<>();
        AtomicReference<WeakReference<ActiveSignalWorkspacePane>> workspaceRef = new AtomicReference<>();
        AtomicReference<WeakReference<SignalInspectorPane>> inspectorRef = new AtomicReference<>();
        AtomicReference<WeakReference<TableView<?>>> catalogTableRef = new AtomicReference<>();
        AtomicReference<WeakReference<TableView<?>>> workspaceTableRef = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        String previousHome = System.getProperty("stellarai.home");
        String previousDb = System.getProperty("stellarai.metadata.db");
        Platform.runLater(() -> {
            DesktopAppService service = null;
            MainShellController shell = null;
            Stage stage = null;
            try {
                Path home = temp.resolve("signals-scene");
                Path db = temp.resolve("signals-scene.db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                populateCatalog(service, 80);
                assertEquals(0, service.signalsModel().favoritesListenerCountForTest());
                assertEquals(0, service.signalsModel().recentsListenerCountForTest());

                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent shellRoot = loader.load();
                shell = loader.getController();
                Scene scene = new Scene(shellRoot, 1440, 900);
                scene.getStylesheets().add(
                        StellarDesktopApp.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setScene(scene);
                stage.show();
                shell.init(service);

                shell.show(NavigationPage.SIGNALS);
                SignalsController controller = (SignalsController) shell.controllerForTest(NavigationPage.SIGNALS);
                assertTrue(service.signalsModel().favoritesListenerCountForTest() >= 2,
                        "browser + inspector must register favorites listeners");
                assertTrue(service.signalsModel().recentsListenerCountForTest() >= 1);
                assertEquals(scene, controller.searchHotkeySceneForTest());
                shellRoot.applyCss();
                shellRoot.layout();
                controller.browserPaneForTest().searchField().requestFocus();
                shellRoot.applyCss();
                shellRoot.layout();

                Parent signalsRoot = controller.signalsRootForTest();
                SignalBrowserPane browser = controller.browserPaneForTest();
                ActiveSignalWorkspacePane workspace = controller.workspacePaneForTest();
                SignalInspectorPane inspector = controller.inspectorPaneForTest();
                TableView<?> catalogTable = browser.catalogTable();
                TableView<?> workspaceTable = workspace.table();
                assertTrue(catalogTable.getItems().size() > 0, "catalog table must have rows so skins exist");
                assertTrue(catalogTable.getSkin() != null, "catalog TableView must build a skin when Scene-attached");

                controllerRef.set(new WeakReference<>(controller));
                rootRef.set(new WeakReference<>(signalsRoot));
                browserRef.set(new WeakReference<>(browser));
                workspaceRef.set(new WeakReference<>(workspace));
                inspectorRef.set(new WeakReference<>(inspector));
                catalogTableRef.set(new WeakReference<>(catalogTable));
                workspaceTableRef.set(new WeakReference<>(workspaceTable));

                shell.show(NavigationPage.PROJECTS_HOME);
                shellRoot.applyCss();
                shellRoot.layout();

                assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertTrue(controller.disposedForTest());
                assertNull(controller.searchHotkeySceneForTest(),
                        "Scene KEY_PRESSED filter must be removed using the remembered Scene");
                assertEquals(0, service.signalsModel().favoritesListenerCountForTest());
                assertEquals(0, service.signalsModel().recentsListenerCountForTest());
                assertNull(catalogTable.getScene(), "catalog TableView must leave the Scene on evict");
                assertNull(workspaceTable.getScene(), "workspace TableView must leave the Scene on evict");
                assertTrue(catalogTable.getItems().isEmpty());
                assertTrue(catalogTable.getColumns().isEmpty());
                if (scene.getFocusOwner() != null) {
                    assertFalse(DesktopSceneGraphRelease.isDescendant(signalsRoot, scene.getFocusOwner()),
                            "Scene.focusOwner must not remain inside the evicted Signals tree");
                }
                assertNoPopupOwns(signalsRoot);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (stage != null) {
                    stage.setScene(null);
                    stage.hide();
                    stage.close();
                }
                if (shell != null) {
                    shell.dispose();
                }
                if (service != null) {
                    service.shutdown();
                }
                shown.countDown();
            }
        });
        try {
            assertTrue(shown.await(50, TimeUnit.SECONDS), "JavaFX scene-attached scenario timed out");
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }
            CountDownLatch flushed = new CountDownLatch(1);
            Platform.runLater(flushed::countDown);
            assertTrue(flushed.await(10, TimeUnit.SECONDS), "JavaFX queue did not flush after Signals eviction");
            assertTrue(awaitCleared(controllerRef.get()), "SignalsController remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(rootRef.get()), "Signals root remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(browserRef.get()), "SignalBrowserPane remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(workspaceRef.get()), "ActiveSignalWorkspacePane remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(inspectorRef.get()), "SignalInspectorPane remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(catalogTableRef.get()), "catalog TableView remained reachable after Scene-attached eviction");
            assertTrue(awaitCleared(workspaceTableRef.get()), "workspace TableView remained reachable after Scene-attached eviction");
        } finally {
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
        }
    }

    private static void populateCatalog(DesktopAppService service, int count) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        List<CanonicalSignal> signals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            signals.add(new CanonicalSignal(
                    "SIG_" + i, "MSG", 1_000L * (i + 1), 0x100, 1, i + 1.0, i + 1L, "rpm", null,
                    SourceReference.ofLine(i + 1)));
        }
        store.accept(signals);
        service.signalsModel().replaceCatalog(store);
    }

    private static void assertNoPopupOwns(Parent root) {
        for (Window window : Window.getWindows()) {
            if (window instanceof PopupWindow popup && popup.getOwnerNode() != null) {
                assertFalse(DesktopSceneGraphRelease.isDescendant(root, popup.getOwnerNode()),
                        "popup window still owns a node inside the evicted Signals tree");
            }
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
}
