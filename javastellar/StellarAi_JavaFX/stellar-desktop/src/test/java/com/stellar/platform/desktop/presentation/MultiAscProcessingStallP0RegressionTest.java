package com.stellar.platform.desktop.presentation;

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
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production isolated-worker path: 20 ASC sources must each terminalize once.
 */
class MultiAscProcessingStallP0RegressionTest {

    private static final int JOB_COUNT = 20;

    @TempDir
    Path tempDir;
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
    @Timeout(90)
    void twentyAscJobsCompleteSequentiallyOnIsolatedWorker() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject("multi-asc-20");
        assertTrue(service.isolateProductionProcessing());
        WorkspaceFile dbc = addDefinition(service, unique("obd.dbc"));
        List<String> sourceIds = new ArrayList<>();
        for (int i = 0; i < JOB_COUNT; i++) {
            sourceIds.add(addAsc(service, unique("run-" + i + ".asc"), dbc).id());
        }
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(JOB_COUNT, service.processingSelectionModel().reviewedPlan().jobs().size());
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 80);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        List<IndependentJobResult> results = service.processingResultRegistry().all();
        assertEquals(JOB_COUNT, results.size());
        assertTrue(results.stream().allMatch(IndependentJobResult::successful));
        Set<String> resultSources = results.stream()
                .map(IndependentJobResult::projectSourceId)
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(sourceIds), resultSources);
        long completed = service.processingSelectionModel().executionJobs().stream()
                .filter(row -> row.status().isSuccessful())
                .count();
        long failed = service.processingSelectionModel().executionJobs().stream()
                .filter(row -> row.status() == JobExecutionStatus.FAILED)
                .count();
        long pending = service.processingSelectionModel().executionJobs().stream()
                .filter(row -> !row.status().isTerminal())
                .count();
        assertEquals(JOB_COUNT, completed);
        assertEquals(0, failed);
        assertEquals(0, pending);
        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCompleted(run.status()))
                .orElse(false));
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        assertFalse(service.isPlanExecutionActive());
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
    }

    private WorkspaceFile addAsc(DesktopAppService service, String name, WorkspaceFile dbc) throws Exception {
        Path asc = DesktopFixtures.copyResource("asc/desktop_engine.asc", tempDir.resolve(name));
        WorkspaceFile source = new WorkspaceFile(asc, FileKind.MEASUREMENT_ASC, Files.size(asc));
        service.workspace().addFile(source);
        service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                asc, name, MeasurementSourceFormat.ASC, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        1, "Channel 1", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 1, 8, 8, false)))),
                List.of()));
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 1, 1, 1.0));
        return source;
    }

    private static void waitForIdle(DesktopAppService service, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "20-ASC isolated run did not reach a terminal state");
    }

    private static String unique(String suffix) {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }
}
