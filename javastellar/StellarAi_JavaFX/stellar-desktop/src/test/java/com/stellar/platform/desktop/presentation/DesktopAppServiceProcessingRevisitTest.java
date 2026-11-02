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
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppServiceProcessingRevisitTest {

    @Test
    void unchangedDecisionsRestoreCompletedResults() throws Exception {
        Path temp = Files.createTempDirectory("proc-revisit");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile sourceA = addReadySource(service, temp, "alpha.mf4", 1, 100L);
        WorkspaceFile sourceB = addReadySource(service, temp, "beta.mf4", 1, 50L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmed(service, sourceA, 1, definition.definitionSourceId(), "map.dbc");
        addConfirmed(service, sourceB, 1, definition.definitionSourceId(), "map.dbc");

        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(ProcessingPageMode.PLAN_REVIEW, service.processingSelectionModel().mode());
        IndependentJobResult a = stored(plan.jobs().get(0), JobExecutionStatus.SUCCESS);
        IndependentJobResult b = stored(plan.jobs().get(1), JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        service.processingResultRegistry().publishJob(a);
        service.processingResultRegistry().publishJob(b);

        service.enterProcessingWorkspaceFromDecision();
        ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, model.mode());
        assertEquals(2, model.executionJobs().size());
        assertSame(a, model.executionJobs().get(0).result().orElseThrow());
        assertSame(b, model.executionJobs().get(1).result().orElseThrow());
        assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, model.executionJobs().get(1).status());
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    void runningExecutionIsNotResetOnRevisit() throws Exception {
        Path temp = Files.createTempDirectory("proc-revisit-run");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile source = addReadySource(service, temp, "alpha.mf4", 1, 100L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmed(service, source, 1, definition.definitionSourceId(), "map.dbc");
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        service.processingSelectionModel().beginExecution(plan);

        service.enterProcessingWorkspaceFromDecision();
        assertEquals(ProcessingPageMode.EXECUTING, service.processingSelectionModel().mode());
        assertEquals(JobExecutionStatus.QUEUED, service.processingSelectionModel().executionJobs().getFirst().status());
        service.executeReviewedPlan(r -> {
        }, () -> {
        });
        assertEquals(ProcessingPageMode.EXECUTING, service.processingSelectionModel().mode());
        assertEquals(1, service.processingSelectionModel().executionJobs().size());
    }

    @Test
    void changedDefinitionDoesNotReusePriorResult() throws Exception {
        Path temp = Files.createTempDirectory("proc-revisit-change");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile sourceA = addReadySource(service, temp, "alpha.mf4", 1, 100L);
        WorkspaceFile sourceB = addReadySource(service, temp, "beta.mf4", 1, 50L);
        WorkspaceFile def1 = addDefinition(service, temp, "one.dbc");
        WorkspaceFile def2 = addDefinition(service, temp, "two.dbc");
        addConfirmed(service, sourceA, 1, def1.definitionSourceId(), "one.dbc");
        addConfirmed(service, sourceB, 1, def1.definitionSourceId(), "one.dbc");
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        IndependentJobResult a = stored(plan.jobs().get(0), JobExecutionStatus.SUCCESS);
        IndependentJobResult b = stored(plan.jobs().get(1), JobExecutionStatus.SUCCESS);
        service.processingResultRegistry().publishJob(a);
        service.processingResultRegistry().publishJob(b);

        addConfirmed(service, sourceB, 1, def2.definitionSourceId(), "two.dbc");
        service.enterProcessingWorkspaceFromDecision();
        ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
        assertEquals(ProcessingPageMode.PARTIAL, model.mode());
        assertEquals(1, model.executionJobs().size());
        assertSame(a, model.executionJobs().getFirst().result().orElseThrow());
        assertEquals(sourceA.id(), model.executionJobs().getFirst().jobKey().projectSourceId());
        ProcessingJob jobB = model.reviewedPlan().jobs().stream()
                .filter(job -> job.projectSourceId().equals(sourceB.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(def2.definitionSourceId().value(), jobB.definitionSourceId().orElseThrow());
        assertTrue(model.executionJobs().stream().noneMatch(row -> row.jobKey().equals(jobB.key())));
    }

    @Test
    void identicalFilenamesDoNotReuseAcrossSourceIds() throws Exception {
        Path temp = Files.createTempDirectory("proc-revisit-dup");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile a = addReadySourceIn(service, temp.resolve("left"), "same.mf4", 1, 10L);
        WorkspaceFile b = addReadySourceIn(service, temp.resolve("right"), "same.mf4", 1, 20L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmed(service, a, 1, definition.definitionSourceId(), "map.dbc");
        addConfirmed(service, b, 1, definition.definitionSourceId(), "map.dbc");
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        ProcessingJob jobA = plan.jobs().stream()
                .filter(job -> job.projectSourceId().equals(a.id()))
                .findFirst()
                .orElseThrow();
        IndependentJobResult storedA = stored(jobA, JobExecutionStatus.SUCCESS);
        service.processingResultRegistry().publishJob(storedA);

        service.enterProcessingWorkspaceFromDecision();
        ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
        assertEquals(ProcessingPageMode.PARTIAL, model.mode());
        assertEquals(1, model.executionJobs().size());
        assertEquals(a.id(), model.executionJobs().getFirst().jobKey().projectSourceId());
        assertFalse(b.id().equals(a.id()));
        assertEquals("same.mf4", a.fileName());
        assertEquals("same.mf4", b.fileName());
    }

    private static IndependentJobResult stored(ProcessingJob job, JobExecutionStatus status) {
        return new IndependentJobResult(
                job.key(),
                job.definitionSourceId().orElse(null),
                job.definitionDisplayName(),
                job.mappingLifecycle(),
                status,
                job.frameCount(), job.frameCount(), job.frameCount(),
                job.frameCount(), 0, 4,
                null, null, null, Duration.ofMillis(12), List.of(), "", null);
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path dir, String name, int channels, long framesPerChannel
    ) throws Exception {
        return addReadySourceIn(service, dir, name, channels, framesPerChannel);
    }

    private static WorkspaceFile addReadySourceIn(
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
        CanSourceInventory inventory = new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, framesPerChannel * channels, channels, true, channelList, List.of());
        service.sourceInventoryModel().put(file.id(), inventory);
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
