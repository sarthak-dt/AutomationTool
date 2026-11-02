package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEF-001: Reprocess Entire Project must rebuild from current active workspace sources.
 */
class ReprocessAfterRemoveLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-10-12T07:00:00Z");

    @TempDir
    Path temp;

    @Test
    void reprocessAfterRemovingFourOfEightKeepsOnlyActiveSources() throws Exception {
        Path db = temp.resolve("reprocess-remove.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            WorkspaceFile dbc = addDefinition(service, "veh.dbc");
            List<WorkspaceFile> sources = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                WorkspaceFile file = addReadySource(service, "run" + i + ".mf4", 1, 50L + i);
                confirm(service, file, dbc.definitionSourceId());
                service.processingSelectionModel().select(SourceChannelRef.of(file.id(), 1));
                sources.add(file);
            }
            service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
            ProjectProcessingPlan original = service.processingSelectionModel()
                    .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
            assertEquals(8, original.jobs().size());
            Map<String, String> originalDefs = original.jobs().stream().collect(Collectors.toMap(
                    ProcessingJob::projectSourceId,
                    job -> job.definitionSourceId().orElse("")));

            store.upsertProject(new ProjectMetadata(
                    service.currentProjectId(), "eight-remove", T0, T0, "PROCESSING_RESULT", null));
            persistHistoricalJobs(store, service.currentProjectId(), original);

            List<WorkspaceFile> removed = sources.subList(4, 8);
            List<WorkspaceFile> kept = sources.subList(0, 4);
            for (WorkspaceFile file : List.copyOf(removed)) {
                assertTrue(service.removeFile(file.id()));
            }

            Set<String> living = service.activeMeasurementSourceIds();
            assertEquals(kept.stream().map(WorkspaceFile::id).collect(Collectors.toSet()), living);
            assertTrue(service.processingSelectionModel().reviewedPlan().jobs().stream()
                    .noneMatch(job -> removed.stream().anyMatch(file -> file.id().equals(job.projectSourceId()))));

            ProjectProcessingPlan reprocess = service.rebuildActiveReprocessPlan();
            Set<String> planSources = reprocess.jobs().stream()
                    .map(ProcessingJob::projectSourceId)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            assertEquals(4, planSources.size());
            assertEquals(4, reprocess.jobs().size(), "job count must match active channels");
            for (WorkspaceFile file : kept) {
                assertTrue(planSources.contains(file.id()), file.fileName());
                assertEquals(originalDefs.get(file.id()),
                        reprocess.jobs().stream()
                                .filter(job -> file.id().equals(job.projectSourceId()))
                                .findFirst()
                                .orElseThrow()
                                .definitionSourceId()
                                .orElse(""),
                        "surviving DBC mapping must be preserved");
            }
            for (WorkspaceFile file : removed) {
                assertFalse(planSources.contains(file.id()), "removed source must not return: " + file.fileName());
            }
            assertTrue(store.listProcessingJobs(service.currentProjectId()).stream()
                    .anyMatch(job -> removed.getFirst().id().equals(job.sourceId())),
                    "historical ledger may retain removed jobs");
            service.shutdown();
        }
    }

    @Test
    void mixedAddRemoveReprocessUsesOnlyLivingSources() throws Exception {
        Path db = temp.resolve("mixed.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            WorkspaceFile dbc = addDefinition(service, "veh.dbc");
            WorkspaceFile a = ready(service, dbc, "A.mf4");
            WorkspaceFile b = ready(service, dbc, "B.mf4");
            WorkspaceFile c = ready(service, dbc, "C.mf4");
            reviewAll(service, a, b, c);
            assertEquals(3, service.processingSelectionModel().reviewedPlan().jobs().size());

            WorkspaceFile d = ready(service, dbc, "D.mf4");
            WorkspaceFile e = ready(service, dbc, "E.mf4");
            service.processingSelectionModel().select(SourceChannelRef.of(d.id(), 1));
            service.processingSelectionModel().select(SourceChannelRef.of(e.id(), 1));
            service.processingSelectionModel().reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
            assertEquals(5, service.processingSelectionModel().reviewedPlan().jobs().size());

            assertTrue(service.removeFile(b.id()));
            assertTrue(service.removeFile(d.id()));

            ProjectProcessingPlan plan = service.rebuildActiveReprocessPlan();
            Set<String> ids = plan.jobs().stream().map(ProcessingJob::projectSourceId).collect(Collectors.toSet());
            assertEquals(Set.of(a.id(), c.id(), e.id()), ids);
            assertFalse(ids.contains(b.id()));
            assertFalse(ids.contains(d.id()));
            service.shutdown();
        }
    }

    @Test
    void saveReopenAfterRemoveStillExcludesRemovedSources() throws Exception {
        Path db = temp.resolve("reopen.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService first = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            WorkspaceFile dbc = addDefinition(first, "veh.dbc");
            List<WorkspaceFile> sources = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sources.add(ready(first, dbc, "src" + i + ".mf4"));
            }
            reviewAll(first, sources.toArray(WorkspaceFile[]::new));
            String projectId = first.currentProjectId();
            store.upsertProject(new ProjectMetadata(projectId, "reopen-remove", T0, T0, "PROCESSING_RESULT", null));
            persistHistoricalJobs(store, projectId, first.processingSelectionModel().reviewedPlan());

            assertTrue(first.removeFile(sources.get(2).id()));
            assertTrue(first.removeFile(sources.get(3).id()));

            DesktopAppService second = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            WorkspaceFile dbc2 = addDefinition(second, "veh.dbc");
            WorkspaceFile kept0 = ready(second, dbc2, "src0.mf4");
            WorkspaceFile kept1 = ready(second, dbc2, "src1.mf4");
            reviewAll(second, kept0, kept1);
            second.processingSelectionModel().select(SourceChannelRef.of("ghost-removed", 1));
            ProjectProcessingPlan stale = second.processingSelectionModel()
                    .reviewPlan(new ProcessingPlanInputAssembler().assemble(second));
            assertTrue(stale.jobs().stream().anyMatch(job -> "ghost-removed".equals(job.projectSourceId()))
                    || stale.jobs().size() >= 2);

            ProjectProcessingPlan reopened = second.rebuildActiveReprocessPlan();
            Set<String> ids = reopened.jobs().stream().map(ProcessingJob::projectSourceId).collect(Collectors.toSet());
            assertEquals(Set.of(kept0.id(), kept1.id()), ids);
            assertFalse(ids.contains(sources.get(2).id()));
            assertFalse(ids.contains(sources.get(3).id()));
            assertFalse(ids.contains("ghost-removed"));
            assertTrue(store.listProcessingJobs(projectId).stream()
                    .anyMatch(job -> sources.get(2).id().equals(job.sourceId())));
            first.shutdown();
            second.shutdown();
        }
    }

    private static void persistHistoricalJobs(
            SqliteProjectMetadataStore store,
            String projectId,
            ProjectProcessingPlan plan
    ) {
        store.upsertProcessingRun(new ProcessingRunMetadata(
                "hist-run", projectId, "COMPLETED", false, plan.jobs().size(), T0, T0.plusSeconds(10), "ok"));
        for (ProcessingJob job : plan.jobs()) {
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    job.key().toString(), projectId, "hist-run", job.projectSourceId(), job.channelId(),
                    "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                    T0, T0, T0.plusSeconds(1), null, ""));
        }
    }

    private WorkspaceFile ready(DesktopAppService service, WorkspaceFile dbc, String name) throws Exception {
        WorkspaceFile file = addReadySource(service, name, 1, 40L);
        confirm(service, file, dbc.definitionSourceId());
        return file;
    }

    private static void reviewAll(DesktopAppService service, WorkspaceFile... files) {
        for (WorkspaceFile file : files) {
            service.processingSelectionModel().select(SourceChannelRef.of(file.id(), 1));
        }
        service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.processingSelectionModel().reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
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
        if (!Files.exists(path)) {
            Files.writeString(path, "VERSION \"\"\n");
        }
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
