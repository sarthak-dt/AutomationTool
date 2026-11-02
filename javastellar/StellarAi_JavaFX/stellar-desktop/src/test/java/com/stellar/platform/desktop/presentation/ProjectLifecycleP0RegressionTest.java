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
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.ProjectRootIndex;
import com.stellar.platform.storage.ProjectWorkspaceCatalog;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * P0-RC-07: processing results reconstruct from persisted artifacts; project delete
 * removes registry/index rows and never resurrects UUID ghost projects.
 */
class ProjectLifecycleP0RegressionTest {

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
    void processingResultSurvivesHydrateControllerRecreateAndRuntimeReopen() throws Exception {
        DesktopAppService service = newNamedService("Result Persist");
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        Capture first = capture(service);
        assertTrue(first.sources > 0, "processed sources must be non-zero");
        assertTrue(first.jobs > 0);
        assertTrue(first.catalog > 0);
        assertTrue(first.frames > 0);
        assertFalse(first.runId.isBlank());
        int stores = service.signalStoreCreateCountForTest.get();

        service.processingResultRegistry().clear();
        service.signalsModel().clear();
        service.hydrateProcessingWorkspace();
        Capture afterHydrate = capture(service);
        assertEquals(first.jobs, afterHydrate.jobs);
        assertEquals(first.sources, afterHydrate.sources);
        assertTrue(afterHydrate.catalog > 0);
        assertTrue(afterHydrate.frames > 0);
        assertEquals(first.runId, afterHydrate.runId);
        assertEquals(stores, service.signalStoreCreateCountForTest.get(),
                "hydration must reopen durable artifacts, not reprocess");

        service.hydrateProcessingWorkspace();
        service.hydrateProcessingWorkspace();
        assertEquals(first.jobs, capture(service).jobs);
        assertEquals(service.workspace().measurementFiles().size(), 2);
        assertEquals(1, service.workspace().definitionFiles().size());

        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.shutdown();
        services.remove(service);

        DesktopAppService reopened = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(reopened);
        assertTrue(reopened.openSavedProject(projectId).opened());
        Capture afterReopen = capture(reopened);
        assertEquals(first.jobs, afterReopen.jobs);
        assertEquals(first.sources, afterReopen.sources);
        assertTrue(afterReopen.catalog > 0);
        assertEquals(afterHydrate.frames, afterReopen.frames);
        assertEquals(0, reopened.signalStoreCreateCountForTest.get());
        assertFalse(reopened.isPlanExecutionActive());
        assertEquals(ProjectJourneyState.ANALYSIS_READY, reopened.projectJourneyState());
        assertTrue(reopened.openCompletedSignalsForExploration());
    }

    @Test
    void deleteNonActiveProjectDoesNotReturnAfterRestart() {
        DesktopAppService service = newNamedService("Project A");
        String idA = service.currentProjectId();
        service.newProject("Project B");
        String idB = service.currentProjectId();
        service.newProject("Project C");
        String idC = service.currentProjectId();
        assertEquals(3, service.savedProjects().size());

        DesktopAppService.ProjectDeleteResult deleted = service.deleteProject(idB);
        assertTrue(deleted.deleted(), String.join("; ", deleted.warnings()));
        Set<String> remaining = ids(service);
        assertEquals(Set.of(idA, idC), remaining);
        assertTrue(service.savedProjects().stream().noneMatch(p -> looksLikeUuidName(p.name())));
        assertFalse(ProjectRootIndex.entries().containsKey(idB));
        assertEquals(idC, service.currentProjectId());
        assertTrue(service.isDurableNamedProject());

        service.shutdown();
        services.remove(service);
        DesktopAppService restarted = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(restarted);
        assertEquals(Set.of(idA, idC), ids(restarted));
        assertTrue(restarted.savedProjects().stream().noneMatch(p -> idB.equals(p.projectId())));
        assertTrue(restarted.savedProjects().stream().noneMatch(p -> looksLikeUuidName(p.name())));
    }

    @Test
    void deleteActiveProjectClearsRuntimeAndLeavesSiblings() {
        DesktopAppService service = newNamedService("Keep A");
        String idA = service.currentProjectId();
        service.newProject("Delete Active");
        String idB = service.currentProjectId();
        service.newProject("Keep C");
        String idC = service.currentProjectId();
        service.openSavedProject(idB);
        assertEquals(idB, service.currentProjectId());

        assertTrue(service.deleteProject(idB).deleted());
        assertFalse(service.isDurableNamedProject());
        assertEquals(Set.of(idA, idC), ids(service));
        assertTrue(service.workspace().measurementFiles().isEmpty());
        assertEquals(ProjectJourneyState.NEW, service.projectJourneyState());
    }

    @Test
    void deleteProcessedProjectDoesNotTouchSiblingArtifactsOrParentStorage() throws Exception {
        DesktopAppService service = newNamedService("Sibling A");
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        String idA = service.currentProjectId();
        Path rootA = ArtifactStorageRefs.jobRoot(
                idA,
                service.processingResultRegistry().all().getFirst().projectSourceId(),
                service.processingResultRegistry().all().getFirst().channelId(),
                service.processingResultRegistry().all().getFirst().definitionSourceId().orElse(""));
        assertTrue(Files.isDirectory(rootA.getParent().getParent()));

        service.newProject("Processed B");
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        String idB = service.currentProjectId();
        Path analysisProjects = AnalysisStorageRoots.configuredProjectsDirectory().orElseThrow();

        service.newProject("Sibling C");
        String idC = service.currentProjectId();
        assertTrue(service.deleteProject(idB).deleted());
        assertTrue(Files.isDirectory(analysisProjects), "must never delete parent Analysis Storage");
        assertTrue(Files.isDirectory(rootA));
        assertEquals(Set.of(idA, idC), ids(service));
    }

    @Test
    void uuidArtifactDirectoryIsNotAUserProject() throws Exception {
        newNamedService("Real");
        String ghost = UUID.randomUUID().toString();
        Path artifacts = AnalysisStorageRoots.projectArtifactsRoot(ghost).resolve("artifacts");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("g1.bin"), "not-a-project");

        DesktopAppService service = services.getFirst();
        assertTrue(service.savedProjects().stream().noneMatch(p -> ghost.equals(p.projectId())));
        assertTrue(service.projectDashboardSummaries().stream().noneMatch(s -> ghost.equals(s.name())));
        assertEquals(1, service.savedProjects().size());
    }

    @Test
    void dashboardTotalsFollowDeleteAndRestart() throws Exception {
        DesktopAppService service = newNamedService("Dash A");
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        assertTrue(service.saveProject().saved());
        String idA = service.currentProjectId();
        ProjectDashboardSummary beforeB = row(service.projectDashboardSummaries(), idA);
        assertTrue(beforeB.signalCount() > 0);
        assertEquals(2, beforeB.measurementFileCount());

        service.newProject("Dash B");
        prepareOneRealSource(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        assertTrue(service.saveProject().saved());
        String idB = service.currentProjectId();

        int totalBefore = service.projectDashboardSummaries().size();
        assertEquals(2, totalBefore);
        int signalsBefore = service.projectDashboardSummaries().stream()
                .mapToInt(ProjectDashboardSummary::signalCount).sum();
        assertTrue(signalsBefore > beforeB.signalCount());

        assertTrue(service.deleteProject(idB).deleted());
        List<ProjectDashboardSummary> afterDelete = service.projectDashboardSummaries();
        assertEquals(1, afterDelete.size());
        assertEquals(beforeB.signalCount(), afterDelete.getFirst().signalCount());
        assertEquals(2, afterDelete.getFirst().measurementFileCount());
        assertEquals(beforeB.canFrameCount(), afterDelete.getFirst().canFrameCount());

        service.shutdown();
        services.remove(service);
        DesktopAppService restarted = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(restarted);
        List<ProjectDashboardSummary> afterRestart = restarted.projectDashboardSummaries();
        assertEquals(1, afterRestart.size());
        assertEquals(idA, afterRestart.getFirst().projectId());
        assertEquals(beforeB.signalCount(), afterRestart.getFirst().signalCount());
    }

    @Test
    void recreateSameDisplayNameDoesNotInheritOldProject() throws Exception {
        DesktopAppService service = newNamedService("Test");
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        String oldId = service.currentProjectId();
        Path oldRoot = ArtifactStorageRefs.jobRoot(
                oldId,
                service.processingResultRegistry().all().getFirst().projectSourceId(),
                service.processingResultRegistry().all().getFirst().channelId(),
                service.processingResultRegistry().all().getFirst().definitionSourceId().orElse(""));
        assertTrue(service.deleteProject(oldId).deleted());

        service.newProject("Test");
        String newId = service.currentProjectId();
        assertNotEquals(oldId, newId);
        assertTrue(service.processingResultRegistry().all().isEmpty());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertFalse(Files.isDirectory(oldRoot) && service.currentProjectId().equals(oldId));
        assertEquals(1, service.savedProjects().size());
        assertEquals("Test", service.savedProjects().getFirst().name());
        assertEquals(newId, service.savedProjects().getFirst().projectId());
    }

    private DesktopAppService newNamedService(String name) {
        if (previousHome == null) {
            previousHome = System.getProperty("stellarai.home");
            System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        }
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject(name);
        return service;
    }

    private void prepareTwoRealSources(DesktopAppService service) throws Exception {
        WorkspaceFile dbc = addDefinition(service, unique("two.dbc"));
        addRealSource(service, unique("a.mf4"), dbc);
        addRealSource(service, unique("b.mf4"), dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(2, service.processingSelectionModel().reviewedPlan().jobs().size());
    }

    private void prepareOneRealSource(DesktopAppService service) throws Exception {
        WorkspaceFile dbc = addDefinition(service, unique("one.dbc"));
        addRealSource(service, unique("one.mf4"), dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size());
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

    private static Capture capture(DesktopAppService service) {
        List<IndependentJobResult> jobs = service.processingResultRegistry().all();
        long frames = jobs.stream().mapToLong(IndependentJobResult::selectedChannelFrames).sum();
        int sources = (int) jobs.stream().map(IndependentJobResult::projectSourceId).distinct().count();
        int catalog = service.signalsModel().catalog() == null
                ? 0
                : service.signalsModel().catalog().entries().size();
        String runId = service.latestPersistedProcessingRun().map(run -> run.runId()).orElse("");
        return new Capture(sources, jobs.size(), catalog, frames, runId);
    }

    private static Set<String> ids(DesktopAppService service) {
        return service.savedProjects().stream().map(p -> p.projectId()).collect(Collectors.toSet());
    }

    private static ProjectDashboardSummary row(List<ProjectDashboardSummary> rows, String projectId) {
        return rows.stream().filter(s -> projectId.equals(s.projectId())).findFirst().orElseThrow();
    }

    private static boolean looksLikeUuidName(String name) {
        if (name == null || name.length() < 8) {
            return false;
        }
        try {
            UUID.fromString(name.contains("...") ? name.substring(0, 36) : name);
            return true;
        } catch (RuntimeException ex) {
            return name.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*");
        }
    }

    private static String unique(String suffix) {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private record Capture(int sources, int jobs, int catalog, long frames, String runId) {
    }
}
