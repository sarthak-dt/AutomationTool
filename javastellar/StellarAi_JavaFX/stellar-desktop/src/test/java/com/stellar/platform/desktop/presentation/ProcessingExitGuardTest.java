package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.MainShellController;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 §29/§30 — processing lives only inside this desktop process, so exiting or switching
 * project while a run is active must be an explicit, cooperative stop.
 */
class ProcessingExitGuardTest {

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
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(60)
    void exitIsAllowedWithoutAskingWhenNothingIsRunning() throws Exception {
        onFx("exit-idle", (service, shell) -> {
            AtomicReference<String> asked = new AtomicReference<>();
            shell.setStopProcessingDecisionForTest(label -> {
                asked.set(label);
                return true;
            });

            assertTrue(shell.confirmStopProcessingIfActive("Stop Processing & Exit"));
            assertNull(asked.get(), "an idle desktop must not interrogate the user");
        });
    }

    @Test
    @Timeout(120)
    void decliningTheStopKeepsTheRunAliveAndBlocksLeaving() throws Exception {
        onFx("exit-declined", (service, shell) -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            service.planExecutionThreadProbeForTest = () -> {
                entered.countDown();
                await(release);
            };
            startRun(service);
            assertTrue(entered.await(20, TimeUnit.SECONDS));

            AtomicReference<String> asked = new AtomicReference<>();
            shell.setStopProcessingDecisionForTest(label -> {
                asked.set(label);
                return false;
            });

            assertFalse(shell.confirmStopProcessingIfActive("Stop Processing & Exit"),
                    "leaving must be refused while the user keeps the run");
            assertEquals("Stop Processing & Exit", asked.get());
            assertTrue(service.isPlanExecutionActive(), "declining must not cancel anything");

            release.countDown();
            waitForIdle(service);
        });
    }

    @Test
    @Timeout(120)
    void acceptingTheStopCancelsCooperativelyBeforeLeaving() throws Exception {
        onFx("exit-accepted", (service, shell) -> {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            service.planExecutionThreadProbeForTest = () -> {
                entered.countDown();
                await(release);
            };
            startRun(service);
            assertTrue(entered.await(20, TimeUnit.SECONDS));

            shell.setStopProcessingDecisionForTest(label -> true);
            assertTrue(shell.confirmStopProcessingIfActive("Stop & Switch"));

            release.countDown();
            waitForIdle(service);
            // Cooperative: the executor unwound itself rather than being killed.
            assertFalse(service.isPlanExecutionActive());
        });
    }

    private static void startRun(DesktopAppService service) throws Exception {
        service.newProject("Guarded Run");
        Path dir = Files.createTempDirectory("exit-guard");
        Path source = dir.resolve("run01.mf4");
        Files.writeString(source, "stub");
        WorkspaceFile measurement = new WorkspaceFile(source, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(measurement);
        service.sourceInventoryModel().put(measurement.id(), new CanSourceInventory(
                source, "run01.mf4", MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 20, 1, true,
                List.of(new CanChannelInventory(1, "Channel 1", 20, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 10, 8, 8, false)))),
                List.of()));
        Path definition = dir.resolve("chassis.dbc");
        Files.writeString(definition, "VERSION \"\"\n");
        WorkspaceFile dbc = new WorkspaceFile(definition, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(dbc);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), "chassis.dbc",
                dbc.definitionSourceId(), "chassis.dbc", 1, 1, 1.0));
        service.enterProcessingWorkspaceFromDecision();
        service.executeReviewedPlan(Runnable::run, () -> { });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
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
        assertTrue(done.await(110, TimeUnit.SECONDS), "JavaFX scenario timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
