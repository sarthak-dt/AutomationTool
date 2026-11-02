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
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.ArtifactStoreCommit;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable commit outcome, not the in-memory decode status, decides whether a channel is complete.
 * Synthetic stores only. Customer measurement files are not used.
 */
class ArtifactPersistenceStatusTest {

    @TempDir
    Path temp;

    @Test
    void emptyStorePromotesAndZeroSignalsStayAWarning() throws Exception {
        Path staging = temp.resolve("empty.staging");
        Path dest = temp.resolve("empty-final");
        GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(staging, "zero-signal.mf4", "");
        store.finishWriteSession();
        ArtifactStoreCommit.promote(staging, dest);
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(dest));

        IndependentJobResult decoded = warningResult(SourceChannelRef.of("mf4", 1), 0);
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, decoded.status());
        assertEquals(0L, decoded.signalsProduced());
        assertTrue(decoded.diagnostics().getFirst().detail().contains("RECOVERY_REQUIRED"));
    }

    @Test
    void rejectedLayoutIsAFailedJobAndIsNotCatalogued() {
        Path staging = temp.resolve("bad.staging");
        assertThrows(IllegalStateException.class, () -> {
            Files.createDirectories(staging);
            ArtifactStoreCommit.promote(staging, temp.resolve("bad-final"));
        });

        IndependentJobResult failed = warningResult(SourceChannelRef.of("mf4", 1), 0)
                .rejectedPersistence("Staging artifact failed layout verification");
        assertEquals(JobExecutionStatus.FAILED, failed.status());
        assertTrue(failed.catalogHandle().isEmpty());
        assertEquals(1, failed.diagnostics().size());
        assertTrue(failed.technicalDetail().contains("layout verification"));
        assertTrue(failed.diagnostics().getFirst().detail().contains("RECOVERY_REQUIRED"));
        assertEquals("mf4", failed.projectSourceId());
        assertEquals(1, failed.channelId());
    }

    @Test
    void mixedBatchCountsTheRejectedChannelAsAFailure() {
        IndependentJobResult kept = warningResult(SourceChannelRef.of("blf", 1), 262_142);
        IndependentJobResult rejected = warningResult(SourceChannelRef.of("mf4", 1), 0)
                .rejectedPersistence("Staging artifact failed layout verification");
        IndependentJobResult rejectedOther = warningResult(SourceChannelRef.of("mf4", 2), 0)
                .rejectedPersistence("Staging artifact failed layout verification");

        String outcome = ProcessingRunStatuses.forJobs(
                ProjectExecutionStatus.SUCCESS, List.of(kept, rejected, rejectedOther));
        assertEquals(ProcessingRunStatuses.COMPLETED_WITH_FAILURES, outcome);

        QuickDecodeSourceResultAssembler assembler = new QuickDecodeSourceResultAssembler();
        List<QuickDecodeSourceResultAssembler.SourceResult> sources =
                assembler.assembleResults(List.of(kept, rejected, rejectedOther));
        assertEquals(2, sources.size());
        int withFailures = (int) sources.stream().filter(source -> source.failedChannels() > 0).count();
        int withWarnings = (int) sources.stream().filter(source -> source.warningChannels() > 0).count();
        assertEquals(1, withFailures);
        assertEquals(1, withWarnings);
        QuickDecodeSourceResultAssembler.SourceResult mf4 = sources.stream()
                .filter(source -> "mf4".equals(source.projectSourceId()))
                .findFirst()
                .orElseThrow();
        assertEquals("ERROR", mf4.resultLabel());
        assertEquals(0, mf4.successfulChannels());
        assertFalse(mf4.hasSignalCatalog());
        QuickDecodeSourceResultAssembler.SourceResult blf = sources.stream()
                .filter(source -> "blf".equals(source.projectSourceId()))
                .findFirst()
                .orElseThrow();
        assertEquals("COMPLETED", blf.resultLabel());
        assertEquals(262_142L, blf.decodedSamples());
    }

    @Test
    void saveReopenShowsTheUnpersistedChannelAsFailed() throws Exception {
        Path home = temp.resolve("home");
        Path db = temp.resolve("meta.db");
        Files.createDirectories(home);
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());

        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), new SqliteProjectMetadataStore(db));
        try {
            service.newProject("BD19");
            Path dir = temp.resolve("sources");
            WorkspaceFile measurement = addReadySource(service, dir, "channels.mf4", 2, 100L);
            WorkspaceFile definition = addDefinition(service, dir, "map.dbc");
            addConfirmed(service, measurement, 1, definition.definitionSourceId(), "map.dbc");
            addConfirmed(service, measurement, 2, definition.definitionSourceId(), "map.dbc");
            service.enterProcessingWorkspaceFromDecision();
            List<ProcessingJob> jobs = service.processingSelectionModel().reviewedPlan().jobs();
            assertEquals(2, jobs.size());

            Instant now = Instant.parse("2026-10-20T14:00:00Z");
            String message = "RECOVERY_REQUIRED: Recovery required for DG (not silent zero)\n"
                    + "Staging artifact failed layout verification";
            try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
                for (ProcessingJob job : jobs) {
                    store.upsertProcessingJob(new ProcessingJobMetadata(
                            job.key().toString(),
                            service.currentProjectId(),
                            "run-bd19",
                            job.projectSourceId(),
                            job.channelId(),
                            job.definitionSourceId().orElse(""),
                            JobExecutionStatus.FAILED,
                            1, 3, 1d, JobExecutionStatus.FAILED.name(), "in-process",
                            now, now, now, null, message));
                }
            }
            service.processingResultRegistry().clear();
            service.enterProcessingWorkspaceFromDecision();

            ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
            assertEquals(ProcessingPageMode.EXECUTION_RESULT, model.mode());
            assertEquals(2, model.failedJobCount());
            assertEquals(0, model.successfulJobCount());
            assertTrue(model.executionJobs().stream()
                    .allMatch(job -> job.status() == JobExecutionStatus.FAILED));
            assertTrue(model.executionJobs().getFirst().result().orElseThrow().catalogHandle().isEmpty());
            assertTrue(model.executionJobs().getFirst().result().orElseThrow().technicalDetail()
                    .contains("RECOVERY_REQUIRED"));
            assertTrue(service.metadataStore().listSignalCatalog(service.currentProjectId()).isEmpty());

            try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
                assertEquals(JobExecutionStatus.FAILED,
                        reopened.findProcessingJob(jobs.getFirst().key().toString()).orElseThrow().status());
                assertTrue(reopened.listSignalCatalog(service.currentProjectId()).isEmpty());
            }
        } finally {
            service.shutdown();
        }
    }

    private static IndependentJobResult warningResult(SourceChannelRef channel, long signals) {
        return new IndependentJobResult(
                ProcessingJobKey.of(channel),
                null,
                "",
                MappingLifecycle.NO_MATCH,
                JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                10, 10, 10, 0, 10, signals,
                null, null, null, Duration.ofMillis(20),
                List.of(new ProcessingPlanDiagnostic(
                        ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                        channel,
                        "RECOVERY_REQUIRED: Recovery required for DG (not silent zero)")),
                "",
                new Object());
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path dir, String name, int channels, long framesPerChannel
    ) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channelList = new ArrayList<>();
        for (int i = 1; i <= channels; i++) {
            channelList.add(new CanChannelInventory(
                    i, "Channel " + i, framesPerChannel, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false), 10, 8, 8, false))));
        }
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, framesPerChannel * channels, channels, true, channelList, List.of()));
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path temp, String name) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static void addConfirmed(
            DesktopAppService service,
            WorkspaceFile measurement,
            int channelId,
            DefinitionSourceId defId,
            String dbcName
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, dbcName, defId, dbcName, 10, 10, 1.0));
    }
}
