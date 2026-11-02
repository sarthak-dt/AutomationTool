package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProjectSwitchTrace;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-switch loading presentation. Hydration stays on the project-switch worker.
 */
class ProjectSwitchLoadingPresentationTest {

    private static final double[][] WINDOW_SIZES = {
            {1706, 900},
            {1440, 900},
            {1280, 800},
            {1100, 720}
    };

    @TempDir
    static Path temp;

    private static DesktopAppService service;
    private static MainShellController shell;
    private static Parent root;
    private static Stage stage;
    private static String css;
    private static Project origin;
    private static Project alpha;
    private static Project beta;
    private static CountDownLatch earlyRelease;
    private static CountDownLatch publishRelease;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);

        Path home = temp.resolve("home");
        Files.createDirectories(home);
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        MainShellController.asyncProjectSwitchForTest = true;
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("loading.db")));
        css = MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm();
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            root = loader.load();
            shell = loader.getController();
            shell.init(service);
            shell.setNewProjectNameForTest("loading-ui");
            stage = new Stage();
            showAt(1280, 800);
        });
        origin = createProject("Origin", "origin.mf4", "origin.dbc");
        alpha = createProject("Alpha", "alpha.mf4", "alpha.dbc");
        beta = createProject("Beta", "beta.mf4", "beta.dbc");
        openAndSettle(origin);
    }

    @BeforeEach
    void restoreObserver() {
        if (shell != null) {
            shell.ensureProjectSwitchObserverForTest();
        }
        clearHolds();
    }

    @AfterAll
    static void shutdown() {
        MainShellController.asyncProjectSwitchForTest = false;
        clearHolds();
        if (service != null) {
            service.setProjectOpenPauseForTest(null);
            service.setProjectOpenBeforePublishPauseForTest(null);
        }
        if (stage != null) {
            CountDownLatch closed = new CountDownLatch(1);
            Platform.runLater(() -> {
                stage.close();
                closed.countDown();
            });
            try {
                closed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (shell != null) {
            CountDownLatch disposed = new CountDownLatch(1);
            Platform.runLater(() -> {
                shell.dispose();
                disposed.countDown();
            });
            try {
                disposed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void loadingOverlayAppearsImmediatelyAndShellStaysStyled() throws Exception {
        openAndSettle(origin);
        armEarlyHold();
        try {
            onFx(() -> {
                shell.requestOpenSavedProject(alpha.id());
                root.applyCss();
                root.layout();
                assertTrue(shell.projectSwitchOverlayVisibleForTest());
                assertEquals("Opening project…", shell.projectSwitchStatusTextForTest());
                assertEquals("", shell.projectSwitchDetailTextForTest());
                assertEquals("Origin", shell.projectNameChromeForTest());

                StackPane content = contentHost();
                assertEquals(1, content.getChildren().size());
                Node panel = content.getChildren().get(0);
                assertTrue(panel.getStyleClass().contains("project-switch-panel"));
                assertFalse(panel instanceof Label);
                assertNotNull(((Region) panel).getBackground());
                assertFalse(((Region) panel).getBackground().getFills().isEmpty());
                assertEquals(AccessibleRole.TEXT, panel.getAccessibleRole());
                assertTrue(panel.getAccessibleText().toLowerCase().contains("opening project"));

                Node card = panel.lookup(".project-switch-card");
                assertNotNull(card);
                assertTrue(card.isVisible());
                assertNotNull(((Region) card).getBackground());
                assertFalse(((Region) card).getBackground().getFills().isEmpty());
                assertTrue(card.getBoundsInParent().getWidth() >= 240, card.getBoundsInParent().toString());
                assertTrue(card.getBoundsInParent().getHeight() >= 80, card.getBoundsInParent().toString());
                assertTrue(card.lookupAll(".progress-bar").isEmpty());

                ProgressIndicator indicator = (ProgressIndicator) panel.lookup(".project-switch-indicator");
                assertNotNull(indicator);
                assertTrue(indicator.isVisible());
                assertTrue(indicator.getProgress() < 0);
                assertEquals(AccessibleRole.PROGRESS_INDICATOR, indicator.getAccessibleRole());
                assertTrue(indicator.getAccessibleText().toLowerCase().contains("opening project"));
                assertEquals(1, content.lookupAll(".progress-indicator").size());
                assertTrue(indicator.getBoundsInParent().getHeight() >= 24);

                Node header = root.lookup(".modern-top-header");
                Node sidebar = root.lookup(".sidebar");
                Node status = root.lookup(".app-status-bar");
                Label brand = (Label) root.lookup(".brand-wordmark");
                assertNotNull(header);
                assertTrue(header.isVisible());
                assertTrue(header.getBoundsInParent().getHeight() >= 40);
                assertNotNull(((Region) header).getBackground());
                assertFalse(((Region) header).getBackground().getFills().isEmpty());
                assertNotNull(sidebar);
                assertTrue(sidebar.isVisible());
                assertTrue(sidebar.getBoundsInParent().getWidth() >= 72);
                assertNotNull(status);
                assertTrue(status.isVisible());
                assertNotNull(brand);
                assertEquals("StellarAI", brand.getText());
                assertTrue(brand.isVisible());
                assertFalse(root.getScene().getStylesheets().isEmpty());
                assertTrue(root.getStyleClass().contains("app-root"));
                assertTrue(contains(sceneBounds(content), sceneBounds(card)));
                assertFalse(overlaps(sceneBounds(header), sceneBounds(card)));
                assertFalse(overlaps(sceneBounds(sidebar), sceneBounds(card)));
            });
            assertTrue(earlyEntered().await(10, TimeUnit.SECONDS));
        } finally {
            releaseAndIdle();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void fxPulsesContinueDuringBackgroundHydration() throws Exception {
        openAndSettle(origin);
        armEarlyHold();
        try {
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            assertTrue(earlyEntered().await(10, TimeUnit.SECONDS));
            int pulses = 0;
            long started = System.nanoTime();
            while (pulses < 3 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)) {
                CountDownLatch pulse = new CountDownLatch(1);
                Platform.runLater(pulse::countDown);
                assertTrue(pulse.await(500, TimeUnit.MILLISECONDS), "JavaFX pulse stalled during hydration");
                pulses++;
            }
            assertTrue(pulses >= 3, "pulses=" + pulses);
            assertTrue(shell.projectSwitchOverlayVisibleForTest());
            assertFalse("JavaFX Application Thread".equals(Thread.currentThread().getName()));
        } finally {
            releaseAndIdle();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void activeCommandsAreDisabledWhileLoadingAndFocusStaysInTheShell() throws Exception {
        openAndSettle(origin);
        onFx(() -> shell.show(NavigationPage.SIGNALS));
        Node signals = shell.pageNodeForTest(NavigationPage.SIGNALS);
        assertNotNull(signals);
        armEarlyHold();
        try {
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            assertTrue(earlyEntered().await(10, TimeUnit.SECONDS));
            Instant savedAt = service.lastSavedAt().orElse(null);
            onFx(() -> {
                root.applyCss();
                root.layout();
                assertTrue(shell.saveProjectButtonForTest().isDisabled());
                for (Button button : shell.permanentNavButtonsForTest()) {
                    assertTrue(button.isDisabled(), button.getText());
                }
                Node selector = root.lookup(".project-selector");
                Button collapse = (Button) root.lookup(".sidebar-collapse");
                assertNotNull(selector);
                assertFalse(selector.isDisable());
                assertFalse(collapse.isDisabled());
                assertNull(signals.getScene());
                Node focus = root.getScene().getFocusOwner();
                if (focus != null) {
                    assertEquals(root.getScene(), focus.getScene());
                    assertFalse(isAncestor(signals, focus));
                }
                Runnable save = root.getScene().getAccelerators().get(
                        new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
                assertNotNull(save);
                save.run();
            });
            assertEquals(savedAt, service.lastSavedAt().orElse(null));
            assertEquals(origin.id(), service.currentProjectId());
            assertEquals("Origin", shell.projectNameChromeForTest());
        } finally {
            releaseAndIdle();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void loadingStaysUntilCurrentGenerationPublishesAndStaleStagesAreIgnored() throws Exception {
        assertEquals("Opening project…", MainShellController.projectSwitchStatusForStage("switch-requested"));
        assertEquals("Loading project metadata", MainShellController.projectSwitchStatusForStage("metadata"));
        assertEquals("Restoring measurements and definitions", MainShellController.projectSwitchStatusForStage("dbc"));
        assertEquals("Restoring saved analysis", MainShellController.projectSwitchStatusForStage("artifacts"));
        assertEquals("Preparing workspace", MainShellController.projectSwitchStatusForStage("graph"));
        assertEquals("Preparing workspace", MainShellController.projectSwitchStatusForStage("graph-restore-skipped"));
        assertEquals("Preparing workspace", MainShellController.projectSwitchStatusForStage("open-complete"));
        assertNull(MainShellController.projectSwitchStatusForStage("ui-ready"));
        assertNull(MainShellController.projectSwitchStatusForStage("publish-skipped"));

        openAndSettle(origin);
        armPublishHold();
        try {
            int hides = shell.projectSwitchHideCountForTest();
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            assertTrue(publishEntered().await(20, TimeUnit.SECONDS));
            onFx(() -> {
                assertTrue(shell.projectSwitchOverlayVisibleForTest());
                assertEquals(alpha.id(), service.currentProjectId());
                assertEquals("Origin", shell.projectNameChromeForTest());
                assertEquals("Origin", shell.statusProjectTextForTest());
                assertEquals("Opening project…", shell.projectSwitchStatusTextForTest());
                assertEquals("Preparing workspace", shell.projectSwitchDetailTextForTest());
                shell.refreshProjectChrome();
                assertEquals("Origin", shell.projectNameChromeForTest());
                long stale = shell.projectSwitchGenerationForTest() + 50;
                ProjectSwitchTrace.stage(stale, "metadata", ProjectSwitchTrace.now(), 0, false);
                ProjectSwitchTrace.stage(shell.projectSwitchGenerationForTest(), "artifacts", ProjectSwitchTrace.now(), 0, true);
            });
            onFx(() -> assertEquals("Preparing workspace", shell.projectSwitchDetailTextForTest()));
            assertEquals(hides, shell.projectSwitchHideCountForTest());
        } finally {
            releaseAndIdle();
        }
        assertEquals(alpha.id(), service.currentProjectId());
        assertEquals("Alpha", shell.projectNameChromeForTest());
        assertFalse(shell.projectSwitchOverlayVisibleForTest());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void successRemovesTheLoadingUiExactlyOnce() throws Exception {
        openAndSettle(origin);
        int hides = shell.projectSwitchHideCountForTest();
        openAndSettle(beta);
        assertEquals(hides + 1, shell.projectSwitchHideCountForTest());
        assertFalse(shell.projectSwitchActiveForTest());
        assertFalse(shell.projectSwitchOverlayVisibleForTest());
        onFx(() -> {
            assertNull(root.lookup(".project-switch-panel"));
            assertEquals(0, contentHost().lookupAll(".progress-indicator").size());
            assertEquals("Beta", shell.projectNameChromeForTest());
            assertFalse(shell.saveProjectButtonForTest().isDisabled());
        });
        assertEquals(beta.id(), service.currentProjectId());
        assertTrue(service.workspace().measurementFiles().stream()
                .anyMatch(file -> "beta.mf4".equals(file.fileName())));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void failureClearsLoadingAndRestoresASafeWorkspace() throws Exception {
        openAndSettle(origin);
        AtomicBoolean sawOverlay = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        service.setOpenHydrationFailureForTest(new IllegalStateException("injected switch failure"));
        service.setProjectOpenBeforePublishPauseForTest(() -> {
            CountDownLatch seen = new CountDownLatch(1);
            Platform.runLater(() -> {
                sawOverlay.set(shell.projectSwitchOverlayVisibleForTest());
                seen.countDown();
            });
            try {
                seen.await(5, TimeUnit.SECONDS);
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            int hides = shell.projectSwitchHideCountForTest();
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            long started = System.nanoTime();
            while (!sawOverlay.get() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15)) {
                CountDownLatch pulse = new CountDownLatch(1);
                Platform.runLater(pulse::countDown);
                pulse.await(1, TimeUnit.SECONDS);
            }
            assertTrue(sawOverlay.get());
            release.countDown();
            awaitInactive();
            assertEquals(hides + 1, shell.projectSwitchHideCountForTest());
            assertFalse(shell.projectSwitchOverlayVisibleForTest());
            assertEquals("OPEN", shell.lastLifecycleErrorKindForTest());
            assertEquals(origin.id(), service.currentProjectId());
            assertEquals(NavigationPage.HOME, shell.visiblePage());
            onFx(() -> {
                assertNull(root.lookup(".project-switch-panel"));
                assertFalse(contentHost().getChildren().isEmpty());
                assertFalse(shell.saveProjectButtonForTest().isDisabled());
                Button workspace = buttonByText("Workspace");
                assertNotNull(workspace);
                assertFalse(workspace.isDisabled());
                assertEquals("Origin", shell.projectNameChromeForTest());
            });
        } finally {
            release.countDown();
            service.setOpenHydrationFailureForTest(null);
            service.setProjectOpenBeforePublishPauseForTest(null);
            awaitInactive();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void rapidSwitchNeverPublishesTheStaleProject() throws Exception {
        openAndSettle(origin);
        armEarlyHold();
        AtomicBoolean sawAlphaName = new AtomicBoolean();
        AtomicBoolean sawAlphaWhileBetaLoading = new AtomicBoolean();
        try {
            int hides = shell.projectSwitchHideCountForTest();
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            assertTrue(earlyEntered().await(10, TimeUnit.SECONDS));
            onFx(() -> {
                assertTrue(shell.projectSwitchOverlayVisibleForTest());
                assertEquals("Origin", shell.projectNameChromeForTest());
                shell.requestOpenSavedProject(beta.id());
                assertTrue(shell.projectSwitchOverlayVisibleForTest());
                assertEquals("Opening project…", shell.projectSwitchStatusTextForTest());
                assertEquals("Origin", shell.projectNameChromeForTest());
            });
            earlyRelease.countDown();
            service.setProjectOpenPauseForTest(null);
            long started = System.nanoTime();
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
                CountDownLatch pulse = new CountDownLatch(1);
                AtomicReference<String> name = new AtomicReference<>();
                AtomicBoolean overlay = new AtomicBoolean();
                Platform.runLater(() -> {
                    name.set(shell.projectNameChromeForTest());
                    overlay.set(shell.projectSwitchOverlayVisibleForTest());
                    pulse.countDown();
                });
                if (!pulse.await(1, TimeUnit.SECONDS)) {
                    continue;
                }
                if ("Alpha".equals(name.get())) {
                    sawAlphaName.set(true);
                }
                if ("Alpha".equals(name.get()) && overlay.get()) {
                    sawAlphaWhileBetaLoading.set(true);
                }
                if (!shell.projectSwitchActiveForTest() && beta.id().equals(service.currentProjectId()) && !overlay.get()) {
                    break;
                }
            }
            assertFalse(sawAlphaName.get());
            assertFalse(sawAlphaWhileBetaLoading.get());
            assertEquals(beta.id(), service.currentProjectId());
            assertEquals("Beta", shell.projectNameChromeForTest());
            assertEquals(hides + 1, shell.projectSwitchHideCountForTest());
            assertFalse(shell.projectSwitchOverlayVisibleForTest());
            assertTrue(service.workspace().measurementFiles().stream()
                    .anyMatch(file -> "beta.mf4".equals(file.fileName())));
            assertFalse(service.workspace().measurementFiles().stream()
                    .anyMatch(file -> "alpha.mf4".equals(file.fileName())));
        } finally {
            releaseAndIdle();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void loadingCardFitsTheShellAtSupportedWindowSizes() throws Exception {
        openAndSettle(origin);
        armEarlyHold();
        try {
            onFx(() -> shell.requestOpenSavedProject(alpha.id()));
            assertTrue(earlyEntered().await(10, TimeUnit.SECONDS));
            onFx(() -> {
                Screen screen = Screen.getPrimary();
                System.out.println("PROJECT_SWITCH_LOADING scale=" + screen.getOutputScaleX()
                        + " dpi=" + screen.getDpi());
                for (double[] size : WINDOW_SIZES) {
                    showAt(size[0], size[1]);
                    Region shellRoot = (Region) root;
                    shellRoot.setMinSize(0, 0);
                    shellRoot.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                    shellRoot.resize(size[0], size[1]);
                    shellRoot.applyCss();
                    shellRoot.layout();
                    assertEquals(size[0], shellRoot.getWidth(), 1.0, "shell width");
                    assertEquals(size[1], shellRoot.getHeight(), 1.0, "shell height");
                    Node header = root.lookup(".modern-top-header");
                    Node sidebar = root.lookup(".sidebar");
                    Node status = root.lookup(".app-status-bar");
                    Node card = root.lookup(".project-switch-card");
                    Node panel = root.lookup(".project-switch-panel");
                    Label name = (Label) root.lookup(".project-selector-value");
                    Label message = (Label) root.lookup(".project-switch-status");
                    assertNotNull(card, "card missing at " + size[0]);
                    assertNotNull(panel);
                    assertTrue(panel.isVisible());
                    assertEquals(1, contentHost().lookupAll(".progress-indicator").size(), "indicators at " + size[0]);
                    Bounds cardBounds = sceneBounds(card);
                    Bounds hostBounds = sceneBounds(contentHost());
                    System.out.println("PROJECT_SWITCH_LOADING size=" + size[0] + "x" + size[1]
                            + " root=" + shellRoot.getWidth() + "x" + shellRoot.getHeight()
                            + " card=" + cardBounds);
                    assertTrue(contains(hostBounds, cardBounds), cardBounds + " outside " + hostBounds);
                    assertFalse(overlaps(sceneBounds(header), cardBounds), "header overlap at " + size[0]);
                    assertFalse(overlaps(sceneBounds(sidebar), cardBounds), "sidebar overlap at " + size[0]);
                    assertFalse(overlaps(sceneBounds(status), cardBounds), "status overlap at " + size[0]);
                    assertTrue(contains(sceneBounds(header), sceneBounds(name)), name.getText());
                    assertTrue(contains(cardBounds, sceneBounds(message)), message.getText());
                    assertFalse(message.getText().isBlank());
                    assertTrue(cardBounds.getWidth() >= 220, cardBounds.toString());
                    assertTrue(cardBounds.getHeight() >= 70, cardBounds.toString());
                }
                showAt(1280, 800);
            });
        } finally {
            try {
                onFx(() -> showAt(1280, 800));
            } finally {
                releaseAndIdle();
            }
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void exitDuringHydrationCancelsOutstandingWork() throws Exception {
        Path home = temp.resolve("exit-home");
        Files.createDirectories(home);
        System.setProperty("stellarai.home", home.toString());
        DesktopAppService exiting = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("exit.db")));
        AtomicReference<MainShellController> exitShell = new AtomicReference<>();
        AtomicReference<Stage> exitStage = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        exiting.setProjectOpenPauseForTest(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<Throwable> fxError = new AtomicReference<>();
        AtomicReference<Thread.UncaughtExceptionHandler> previousHandler = new AtomicReference<>();
        try {
            onFx(() -> {
                Thread thread = Thread.currentThread();
                previousHandler.set(thread.getUncaughtExceptionHandler());
                thread.setUncaughtExceptionHandler((failed, error) -> {
                    fxError.compareAndSet(null, error);
                    Thread.UncaughtExceptionHandler previous = previousHandler.get();
                    if (previous != null) {
                        previous.uncaughtException(failed, error);
                    }
                });
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent exitRoot = loader.load();
                MainShellController controller = loader.getController();
                controller.init(exiting);
                controller.setNewProjectNameForTest("exit-ui");
                exitShell.set(controller);
                Stage exitWindow = new Stage();
                Scene scene = new Scene(exitRoot, 1100, 720);
                scene.getStylesheets().add(css);
                exitWindow.setScene(scene);
                exitWindow.show();
                exitStage.set(exitWindow);
            });
            Project created = createProjectOn(exiting, "Exit", "exit.mf4", "exit.dbc");
            onFx(() -> exitShell.get().requestOpenSavedProject(created.id()));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            onFx(() -> assertTrue(exitShell.get().projectSwitchOverlayVisibleForTest()));
            long during = projectSwitchThreads();
            AtomicBoolean closed = new AtomicBoolean();
            onFx(() -> closed.set(exitShell.get().confirmExitRequest()));
            assertTrue(closed.get());
            onFx(() -> {
                exitShell.get().dispose();
                exitStage.get().close();
            });
            exiting.setProjectOpenPauseForTest(null);
            exiting.shutdown();
            release.countDown();
            long after = projectSwitchThreads();
            assertTrue(after < during, "project-switch threads " + during + " -> " + after);
            for (int i = 0; i < 3; i++) {
                CountDownLatch pulse = new CountDownLatch(1);
                Platform.runLater(pulse::countDown);
                assertTrue(pulse.await(2, TimeUnit.SECONDS));
            }
            onFx(() -> assertFalse(exitShell.get().projectSwitchActiveForTest()));
            assertNull(fxError.get());
        } finally {
            release.countDown();
            exiting.setProjectOpenPauseForTest(null);
            try {
                exiting.shutdown();
            } catch (RuntimeException ignored) {
                // already shut down
            }
            onFx(() -> Thread.currentThread().setUncaughtExceptionHandler(previousHandler.get()));
            shell.ensureProjectSwitchObserverForTest();
            System.setProperty("stellarai.home", temp.resolve("home").toString());
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void switchDoesNotRetainOldControllersOrRepeatListeners() throws Exception {
        openAndSettle(origin);
        onFx(() -> shell.show(NavigationPage.SIGNALS));
        SignalsController signals = (SignalsController) shell.controllerForTest(NavigationPage.SIGNALS);
        Node signalNode = shell.pageNodeForTest(NavigationPage.SIGNALS);
        assertNotNull(signals);
        assertNotNull(signalNode);
        onFx(() -> shell.show(NavigationPage.GRAPHS));
        assertTrue(signals.disposedForTest());
        assertNull(signalNode.getScene());
        Object graphs = shell.controllerForTest(NavigationPage.GRAPHS);
        Node graphNode = shell.pageNodeForTest(NavigationPage.GRAPHS);
        assertNotNull(graphs);
        assertNotNull(graphNode);
        armEarlyHold();
        try {
            onFx(() -> shell.requestOpenSavedProject(beta.id()));
            assertTrue(earlyEntered().await(15, TimeUnit.SECONDS));
            onFx(() -> {
                assertTrue(signals.disposedForTest());
                assertFalse(shell.pageLoadedForTest(NavigationPage.SIGNALS));
                assertFalse(shell.pageLoadedForTest(NavigationPage.GRAPHS));
                assertNull(signalNode.getScene());
                assertNull(graphNode.getScene());
                assertFalse(contentHost().getChildren().contains(signalNode));
                assertFalse(contentHost().getChildren().contains(graphNode));
                assertEquals(1, shell.projectSwitchObserverInstallsForTest());
            });
        } finally {
            releaseAndIdle();
        }
        openAndSettle(alpha);
        openAndSettle(beta);
        assertEquals(1, shell.projectSwitchObserverInstallsForTest());
        assertTrue(service.signalsModel().favoritesListenerCountForTest() <= 2,
                "favorites listeners " + service.signalsModel().favoritesListenerCountForTest());
        onFx(() -> {
            if (shell.pageLoadedForTest(NavigationPage.GRAPHS)) {
                assertNotSame(graphs, shell.controllerForTest(NavigationPage.GRAPHS));
            }
            if (shell.pageLoadedForTest(NavigationPage.SIGNALS)) {
                assertNotSame(signals, shell.controllerForTest(NavigationPage.SIGNALS));
            }
        });
    }

    private static void armEarlyHold() {
        CountDownLatch entered = new CountDownLatch(1);
        earlyRelease = new CountDownLatch(1);
        service.setProjectOpenBeforePublishPauseForTest(null);
        service.setProjectOpenPauseForTest(() -> {
            entered.countDown();
                try {
                    earlyRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
        });
        earlyEntered = entered;
    }

    private static CountDownLatch earlyEntered;
    private static CountDownLatch publishEntered;

    private static CountDownLatch earlyEntered() {
        return earlyEntered;
    }

    private static void armPublishHold() {
        CountDownLatch entered = new CountDownLatch(1);
        publishRelease = new CountDownLatch(1);
        service.setProjectOpenPauseForTest(null);
        service.setProjectOpenBeforePublishPauseForTest(() -> {
            entered.countDown();
                try {
                    publishRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
        });
        publishEntered = entered;
    }

    private static CountDownLatch publishEntered() {
        return publishEntered;
    }

    private static void releaseAndIdle() throws Exception {
        if (earlyRelease != null) {
            earlyRelease.countDown();
        }
        if (publishRelease != null) {
            publishRelease.countDown();
        }
        clearHolds();
        awaitInactive();
    }

    private static void clearHolds() {
        if (service != null) {
            service.setProjectOpenPauseForTest(null);
            service.setProjectOpenBeforePublishPauseForTest(null);
        }
    }

    private static void openAndSettle(Project project) throws Exception {
        clearHolds();
        onFx(() -> shell.requestOpenSavedProject(project.id()));
        awaitSettled(project.id());
        assertEquals(project.name(), shell.projectNameChromeForTest());
    }

    private static void awaitSettled(String projectId) throws Exception {
        long started = System.nanoTime();
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) {
            CountDownLatch pulse = new CountDownLatch(1);
            AtomicBoolean settled = new AtomicBoolean();
            Platform.runLater(() -> {
                settled.set(!shell.projectSwitchActiveForTest()
                        && projectId.equals(service.currentProjectId())
                        && !shell.projectSwitchOverlayVisibleForTest());
                pulse.countDown();
            });
            if (pulse.await(1, TimeUnit.SECONDS) && settled.get()) {
                return;
            }
        }
        assertEquals(projectId, service.currentProjectId());
        assertFalse(shell.projectSwitchActiveForTest());
        assertFalse(shell.projectSwitchOverlayVisibleForTest());
    }

    private static void awaitInactive() throws Exception {
        long started = System.nanoTime();
        while (shell != null && shell.projectSwitchActiveForTest()
                && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) {
            CountDownLatch pulse = new CountDownLatch(1);
            Platform.runLater(pulse::countDown);
            pulse.await(1, TimeUnit.SECONDS);
        }
    }

    private static void showAt(double width, double height) {
        Scene previous = root.getScene();
        if (previous != null && previous.getRoot() == root) {
            previous.setRoot(new javafx.scene.layout.Pane());
        }
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().setAll(css);
        stage.setScene(scene);
        stage.setMinWidth(width);
        stage.setMinHeight(height);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.show();
        Region shellRoot = (Region) root;
        shellRoot.setMinSize(0, 0);
        shellRoot.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shellRoot.applyCss();
        shellRoot.resize(width, height);
        shellRoot.layout();
    }

    private static StackPane contentHost() {
        return (StackPane) root.lookup(".content-host");
    }

    private static Button buttonByText(String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return inner.getWidth() > 0
                && inner.getHeight() > 0
                && inner.getMinX() >= outer.getMinX() - 1
                && inner.getMinY() >= outer.getMinY() - 1
                && inner.getMaxX() <= outer.getMaxX() + 1
                && inner.getMaxY() <= outer.getMaxY() + 1;
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.getMinX() < b.getMaxX() - 1
                && b.getMinX() < a.getMaxX() - 1
                && a.getMinY() < b.getMaxY() - 1
                && b.getMinY() < a.getMaxY() - 1;
    }

    private static boolean isAncestor(Node ancestor, Node node) {
        Node current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static long projectSwitchThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("stellar-project-switch") && thread.isAlive())
                .count();
    }

    private static Project createProject(String name, String measurement, String dbc) throws Exception {
        return createProjectOn(service, name, measurement, dbc);
    }

    private static Project createProjectOn(DesktopAppService target, String name, String measurement, String dbc)
            throws Exception {
        AtomicReference<Project> created = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                target.newProject(name);
                Path measurementPath = temp.resolve(measurement);
                Path dbcPath = temp.resolve(dbc);
                Files.writeString(measurementPath, measurement);
                Files.writeString(dbcPath, "VERSION \"\"");
                target.workspace().addFile(WorkspaceFile.restore(
                        measurement, measurementPath, FileKind.MEASUREMENT_MF4, Files.size(measurementPath)));
                target.workspace().addFile(WorkspaceFile.restore(
                        dbc, dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath)));
                assertTrue(target.saveProject().saved(), name);
                created.set(new Project(target.currentProjectId(), name, measurement, dbc));
            } catch (Throwable ex) {
                error.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return created.get();
    }

    private static void onFx(FxBody body) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable ex) {
                error.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private record Project(String id, String name, String measurement, String dbc) {
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}
