package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.support.MainShellFxScenario;
import com.stellar.platform.desktop.ui.MainShellController;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellFxLifecycleIsolationTest {

    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    @Timeout(60)
    void disposeRemovesSaveListenerBeforeShutdown() throws Exception {
        MainShellFxScenario.run(temp, "listener", (service, shell) -> {
            assertEquals(1, service.saveCoordinatorForTest().listenerCountForTest());
            shell.dispose();
            assertEquals(0, service.saveCoordinatorForTest().listenerCountForTest());
        });
    }

    @Test
    @Timeout(60)
    void saveAndCloseLeavesNoPendingAutosaveOrStellarThreads() throws Exception {
        Path db = temp.resolve("save-close.db");
        MainShellFxScenario.run(temp, "save-close", (service, shell) -> {
            service.newProject("Lifecycle Save");
            service.noteLogicalChange();
            assertTrue(service.saveCoordinatorForTest().hasPendingAutosave());
            shell.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.SAVE);
            assertTrue(shell.closeActiveProject());
            assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
            assertFalse(service.saveCoordinatorForTest().hasPendingAutosave());
        });
        assertTrue(Files.deleteIfExists(db), "SQLite file must be unlocked after shutdown");
        Files.deleteIfExists(Path.of(db + "-wal"));
        Files.deleteIfExists(Path.of(db + "-shm"));
        JavaFxTestSupport.assertNoLiveStellarThreads("after save-close");
    }

    @Test
    @Timeout(60)
    void dirtyAutosaveIsCancelledOnTeardown() throws Exception {
        MainShellFxScenario.run(temp, "cancel-auto", (service, shell) -> {
            service.newProject("Pending Autosave");
            service.noteLogicalChange();
            assertTrue(service.saveCoordinatorForTest().hasPendingAutosave());
        });
        JavaFxTestSupport.assertNoLiveStellarThreads("after cancelled autosave");
    }

    @Test
    @Timeout(60)
    void secondScenarioPulseIsNotStarvedByPriorTeardown() throws Exception {
        MainShellFxScenario.run(temp, "first-pulse", (service, shell) -> {
            service.newProject("First Pulse");
            service.noteLogicalChange();
            shell.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.SAVE);
            assertTrue(shell.closeActiveProject());
        });

        AtomicLong queuedAt = new AtomicLong();
        AtomicLong startedAt = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        queuedAt.set(System.nanoTime());
        Platform.runLater(() -> {
            startedAt.set(System.nanoTime());
            started.countDown();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS),
                "next FX pulse did not start within 5s after teardown");
        long waitMs = TimeUnit.NANOSECONDS.toMillis(startedAt.get() - queuedAt.get());
        assertTrue(waitMs < 5_000L, "second pulse waited " + waitMs + "ms");

        MainShellFxScenario.run(temp, "second-pulse", (service, shell) -> {
            shell.setNewProjectNameForTest("Second Pulse");
            shell.requestNewProject();
            assertTrue(service.isDurableNamedProject());
        });
    }

    @Test
    @Timeout(60)
    void shutdownJoinsExecutorsOffTheFxThread() throws Exception {
        Path home = temp.resolve("join-home");
        Path db = temp.resolve("join.db");
        Files.createDirectories(home);
        String previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(db));
        try {
            JavaFxTestSupport.runOnFx("join-init", () -> {
                FXMLLoader loader = new FXMLLoader(
                        MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                service.newProject("Join Executors");
                shell.dispose();
            });
            assertFalse(Platform.isFxApplicationThread(), "shutdown must run on the test thread");
            service.shutdown();
            assertTrue(service.saveCoordinatorForTest().schedulerTerminatedForTest());
            assertEquals(0, service.saveCoordinatorForTest().listenerCountForTest());
            assertFalse(service.saveCoordinatorForTest().hasPendingAutosave());
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.assertNoLiveStellarThreads("after explicit shutdown");
            assertTrue(Files.deleteIfExists(db));
        } finally {
            if (previousHome == null) {
                System.clearProperty("stellarai.home");
            } else {
                System.setProperty("stellarai.home", previousHome);
            }
        }
    }
}
