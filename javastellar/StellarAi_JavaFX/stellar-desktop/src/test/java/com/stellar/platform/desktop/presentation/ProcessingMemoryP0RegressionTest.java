package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.worker.LocalWorkerIsolation;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-RC-08: GUI production processing stays process-isolated, the desktop coordinator
 * is not the worker JVM, and repeated runs do not retain extra job handles.
 */
class ProcessingMemoryP0RegressionTest {

    @TempDir Path tempDir;
    private final List<DesktopAppService> services = new ArrayList<>();
    private String previousHome;

    @AfterEach
    void tearDown() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        services.clear();
        AnalysisStorageRoots.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    void productionFactoryEnablesProcessIsolationAndDoesNotUseInProcessDecode() {
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        assertTrue(service.isolateProductionProcessing());
        assertFalse(service.processingCoordinatorIsShutdown());
    }

    @Test
    @Timeout(45)
    void startingIsolatedWorkerDoesNotShutdownDesktopCoordinator() throws Exception {
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean survivedPastShutdownWindow = new AtomicBoolean(false);
        service.submitProcessingCoordinatorForTest(() -> {
            service.ensureIsolatedWorkerForTest();
            entered.countDown();
            try {
                Thread.sleep(6_500L);
                survivedPastShutdownWindow.set(
                        !Thread.currentThread().isInterrupted()
                                && !service.processingCoordinatorIsShutdown());
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(20, TimeUnit.SECONDS), "isolated worker must start on the coordinator thread");
        assertFalse(service.processingCoordinatorIsShutdown());
        long workerPid = service.isolatedWorkerPid();
        assertTrue(workerPid > 0L, "GUI production processing must spawn a worker JVM");
        assertNotEquals(ProcessHandle.current().pid(), workerPid);
        assertTrue(service.localProcessingWorker() instanceof ProcessIsolatedLocalWorker);
        assertEquals(LocalWorkerIsolation.PROCESS_ISOLATED, service.localProcessingWorker().isolation());
        Thread.sleep(7_000L);
        assertTrue(survivedPastShutdownWindow.get(),
                "desktop coordinator must keep running after isolated-worker start (was shutting down its own pool after 5s)");
        assertFalse(service.processingCoordinatorIsShutdown());
        hold.countDown();
    }

    @Test
    void repeatedProcessCompleteDoesNotGrowRegistryBeyondJobCount() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        int expectedJobs = service.processingSelectionModel().reviewedPlan().jobs().size();
        assertTrue(expectedJobs > 0);
        for (int i = 0; i < 3; i++) {
            if (i == 0) {
                service.executeReviewedPlan(Runnable::run, () -> { });
            } else {
                service.forceReprocessReviewedPlan(Runnable::run, () -> { });
            }
            waitForIdle(service);
            assertEquals(expectedJobs, service.processingResultRegistry().all().size(),
                    "run " + i + " must not retain extra job results");
            assertTrue(service.signalsModel().catalog().entries().size() > 0);
        }
        assertEquals(0, liveWorkerMainCount(),
                "in-process test path must not leak isolated worker JVMs");
    }

    private DesktopAppService newNamedService() {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject("P0-RC-08");
        return service;
    }

    private void prepareTwoRealSources(DesktopAppService service) throws Exception {
        WorkspaceFile dbc = addDefinition(service, "mem.dbc");
        addRealSource(service, "mem-a.mf4", dbc);
        addRealSource(service, "mem-b.mf4", dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
    }

    private WorkspaceFile addRealSource(DesktopAppService service, String name, WorkspaceFile dbc) throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve(name));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                mf4, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        0, "Channel 0", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(0, 0x100L, false), 1, 8, 8, false)))),
                List.of()));
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 0),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 1, 1, 1.0));
        return source;
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static long liveWorkerMainCount() {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .map(p -> p.info().commandLine().orElse(p.info().command().orElse("")))
                .filter(cmd -> cmd.contains("StellarLocalWorkerMain"))
                .count();
    }
}
