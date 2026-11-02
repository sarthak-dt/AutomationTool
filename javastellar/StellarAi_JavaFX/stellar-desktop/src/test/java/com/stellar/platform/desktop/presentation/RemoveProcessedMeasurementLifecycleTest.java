package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remove Measurement from an already processed project. Remaining sources keep their
 * results and elapsed; the dropped source leaves the active view without a reprocess.
 */
class RemoveProcessedMeasurementLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    @TempDir
    Path temp;

    @Test
    void removingOneProcessedSourceLeavesTheOthersIntact() throws Exception {
        Path db = temp.resolve("remove.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            WorkspaceFile a = addReadySource(service, "runA.mf4", 1, 100L);
            WorkspaceFile b = addReadySource(service, "runB.mf4", 1, 80L);
            WorkspaceFile c = addReadySource(service, "runC.mf4", 1, 60L);
            WorkspaceFile d = addReadySource(service, "runD.mf4", 1, 40L);
            WorkspaceFile dbc = addDefinition(service, "veh.dbc");
            confirm(service, a, dbc.definitionSourceId());
            confirm(service, b, dbc.definitionSourceId());
            confirm(service, c, dbc.definitionSourceId());
            confirm(service, d, dbc.definitionSourceId());
            service.processingSelectionModel().select(SourceChannelRef.of(a.id(), 1));
            service.processingSelectionModel().select(SourceChannelRef.of(b.id(), 1));
            service.processingSelectionModel().select(SourceChannelRef.of(c.id(), 1));
            service.processingSelectionModel().select(SourceChannelRef.of(d.id(), 1));
            service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
            ProjectProcessingPlan plan = service.processingSelectionModel()
                    .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));

            Map<String, ProcessingJob> bySource = plan.jobs().stream()
                    .collect(Collectors.toMap(ProcessingJob::projectSourceId, job -> job));
            IndependentJobResult ra = result(bySource.get(a.id()).key(), a, Duration.ofSeconds(9), 100);
            IndependentJobResult rb = result(bySource.get(b.id()).key(), b, Duration.ofSeconds(11), 80);
            IndependentJobResult rc = result(bySource.get(c.id()).key(), c, Duration.ofSeconds(6), 60);
            IndependentJobResult rd = result(bySource.get(d.id()).key(), d, Duration.ofSeconds(4), 40);
            service.processingResultRegistry().publishJob(ra);
            service.processingResultRegistry().publishJob(rb);
            service.processingResultRegistry().publishJob(rc);
            service.processingResultRegistry().publishJob(rd);
            service.processingSelectionModel().beginExecution(plan);
            service.processingSelectionModel().updateJobStatus(ra.jobKey(), ra);
            service.processingSelectionModel().updateJobStatus(rb.jobKey(), rb);
            service.processingSelectionModel().updateJobStatus(rc.jobKey(), rc);
            service.processingSelectionModel().updateJobStatus(rd.jobKey(), rd);
            service.processingSelectionModel().completeExecution(new MultiSourceProcessingResult(
                    plan.planId(), T0, ProjectExecutionStatus.SUCCESS,
                    List.of(ra, rb, rc, rd), List.of(), Duration.ofSeconds(26)));

            String projectId = service.currentProjectId();
            store.upsertProject(new ProjectMetadata(projectId, "remove-c", T0, T0, "PROCESSING_RESULT", null));
            ProcessingRunMetadata run = new ProcessingRunMetadata(
                    "run-1", projectId, "COMPLETED", false, 4, T0, T0.plusSeconds(26), "ok");
            store.upsertProcessingRun(run);
            persistWindow(store, projectId, bySource.get(a.id()), T0, T0.plusSeconds(9));
            persistWindow(store, projectId, bySource.get(b.id()), T0.plusSeconds(9), T0.plusSeconds(20));
            persistWindow(store, projectId, bySource.get(c.id()), T0.plusSeconds(20), T0.plusSeconds(26));
            persistWindow(store, projectId, bySource.get(d.id()), T0.plusSeconds(26), T0.plusSeconds(30));
            service.rememberPersistedRunForTest(run);

            new QuickDecodeSignalsViewAssembler()
                    .assembleResults(service.processingResultRegistry().all(), id ->
                            service.workspace().findFile(id).map(WorkspaceFile::fileName).orElse(id))
                    .ifPresent(view -> service.signalsModel().replaceCatalog(view.store(), view.hint(), view.context()));
            SignalCatalogKey keyA = catalogKey(a.id(), "SpeedA");
            SignalCatalogKey keyC = catalogKey(c.id(), "SpeedC");
            service.signalsModel().addToWorkspace(List.of(keyA, keyC));
            service.signalsModel().setBrowserFilter(new SignalBrowserFilter.State(
                    SignalBrowserFilter.SourceFilter.ALL, "", "", "", false, false, c.id(), ""));
            service.selectAnalysisScope(AnalysisScope.single(c.id(), c.fileName()));
            service.graphInvestigation().hide(keyC);
            Duration elapsedA = ra.elapsed();
            Duration elapsedB = rb.elapsed();
            Duration elapsedD = rd.elapsed();

            assertTrue(service.removeFile(c.id()));

            Set<String> living = service.workspace().measurementFiles().stream()
                    .map(WorkspaceFile::id)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(a.id(), b.id(), d.id()), living);
            assertFalse(living.contains(c.id()));
            assertTrue(service.processingResultRegistry().all().stream()
                    .noneMatch(job -> c.id().equals(job.projectSourceId())));
            assertEquals(3, service.processingResultRegistry().all().size());
            assertEquals(elapsedA, service.processingResultRegistry().get(ra.jobKey()).orElseThrow().elapsed());
            assertEquals(elapsedB, service.processingResultRegistry().get(rb.jobKey()).orElseThrow().elapsed());
            assertEquals(elapsedD, service.processingResultRegistry().get(rd.jobKey()).orElseThrow().elapsed());
            assertTrue(service.processingSelectionModel().executionJobs().stream()
                    .noneMatch(row -> c.id().equals(row.jobKey().projectSourceId())));

            Map<String, ProcessingJobTiming.SourceWindow> timing = service.persistedSourceTiming();
            assertEquals(Duration.ofSeconds(9), timing.get(a.id()).elapsed().orElseThrow());
            assertEquals(Duration.ofSeconds(11), timing.get(b.id()).elapsed().orElseThrow());
            assertEquals(Duration.ofSeconds(4), timing.get(d.id()).elapsed().orElseThrow());
            assertFalse(timing.containsKey(c.id()), "removed source elapsed must leave the active summary");

            List<MeasurementCatalogScope.Ref> dropdown = MeasurementCatalogScope.measurements(
                    service.signalsModel().catalog().entries(), service.signalsModel().catalogStore());
            Set<String> dropdownIds = dropdown.stream().map(MeasurementCatalogScope.Ref::identity).collect(Collectors.toSet());
            assertEquals(Set.of(a.id(), b.id(), d.id()), dropdownIds);
            assertTrue(service.signalsModel().catalog().entries().stream()
                    .noneMatch(entry -> c.id().equals(entry.key().sourceFile())));
            assertTrue(service.signalsModel().workspaceKeys().stream()
                    .noneMatch(key -> c.id().equals(key.sourceFile())));
            assertTrue(service.signalsModel().workspaceKeys().stream()
                    .map(SignalCatalogKey::sourceFile)
                    .filter(source -> source != null && !source.isBlank())
                    .allMatch(source -> living.contains(source)
                            || com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(source)));
            assertTrue(service.signalsModel().browserFilter().sourceFile().isBlank());
            assertTrue(service.graphInvestigation().hiddenKeys().stream()
                    .noneMatch(key -> c.id().equals(key.sourceFile())));
            assertTrue(service.analysisScope().isAll());

            AppliedDbcPresentation.Summary applied = AppliedDbcPresentation.fromResults(
                    service.processingResultRegistry().all(), "");
            assertEquals(3, applied.measurementCount());
            assertFalse(applied.detail().toLowerCase().contains("runc"));

            assertTrue(store.listProcessingJobs(projectId).stream()
                    .anyMatch(job -> c.id().equals(job.sourceId())),
                    "historical ledger may keep the removed job");
            Map<String, ProcessingJobTiming.SourceWindow> reopened = ProcessingJobTiming.sourceWindows(
                    store.listProcessingJobs(projectId), List.of(run));
            reopened.entrySet().removeIf(entry -> !living.contains(entry.getKey()));
            assertEquals(Duration.ofSeconds(9), reopened.get(a.id()).elapsed().orElseThrow());
            assertEquals(Duration.ofSeconds(11), reopened.get(b.id()).elapsed().orElseThrow());
            assertEquals(Duration.ofSeconds(4), reopened.get(d.id()).elapsed().orElseThrow());
            assertFalse(reopened.containsKey(c.id()));
            service.shutdown();
        }
    }

    private static void persistWindow(
            SqliteProjectMetadataStore store,
            String projectId,
            ProcessingJob job,
            Instant started,
            Instant finished
    ) {
        store.upsertProcessingJob(new ProcessingJobMetadata(
                job.key().toString(), projectId, "run-1", job.projectSourceId(), job.channelId(),
                "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                T0, started, finished, null, ""));
    }

    private static IndependentJobResult result(
            ProcessingJobKey key, WorkspaceFile source, Duration elapsed, long frames
    ) {
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(source.id(), "veh.dbc");
        catalog.accept(List.of(new CanonicalSignal(
                "Speed" + source.fileName().charAt(3), "Msg", 10L, 0x10, 1, 1.0, frames,
                "km/h", null, SourceReference.ofLine(1))));
        return new IndependentJobResult(
                key, "d", "veh.dbc", MappingLifecycle.CONFIRMED, JobExecutionStatus.SUCCESS,
                frames, frames, frames, frames, 0, frames,
                1L, 2L, null, elapsed, List.of(), "", catalog);
    }

    private static SignalCatalogKey catalogKey(String sourceId, String name) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Msg", 0x10, 1, sourceId, "veh.dbc");
    }

    private WorkspaceFile addReadySource(DesktopAppService service, String name, int channelId, long frames)
            throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, 31, true,
                List.of(new CanChannelInventory(channelId, "Channel " + channelId, frames, 31, 31, 0, 0,
                        List.of(new CanMessageInventory(
                                new CanMessageKey(channelId, 0x100L, false), 10, 8, 8, false)))),
                List.of()));
        return file;
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static void confirm(DesktopAppService service, WorkspaceFile measurement, DefinitionSourceId defId) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "veh.dbc", defId, "veh.dbc", 1, 1, 1.0));
    }
}
