package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
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
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto Decode and Custom Decode may process native/raw outputs when a CAN channel has no DBC.
 * An ambiguous Review channel still requires an explicit DBC choice.
 */
class AutoDecodeNoDbcEligibilityTest {

    @Test
    void autoDecodeMf4WithCanAndNoDbcIsProcessableWithWarning() throws Exception {
        Path temp = Files.createTempDirectory("auto-no-dbc");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 128_788L);
        installNoDbc(service, source, 1, 3);

        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
        DesktopAppService.DecisionProcessingReadiness readiness = service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(0, readiness.unresolvedChannels());
        assertEquals(1, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.dbcConfiguredChannels());
        assertTrue(readiness.readyTooltip().contains("DBC-decoded CAN signals will not be generated"));

        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertEquals(1, plan.jobs().size());
        ProcessingJob job = plan.jobs().getFirst();
        assertTrue(job.executable());
        assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
        assertTrue(job.definitionSourceId().isEmpty());

        ProcessingReviewSummary review = ProcessingReviewSummaryFactory.build(service);
        assertTrue(review.readyToProcess());
        assertEquals("Ready with warnings", review.readinessText());
        assertEquals(0, review.definitionCount());
        assertTrue(review.issues().stream().anyMatch(issue ->
                issue.severity() == ReviewIssueSeverity.WARNING
                        && issue.title().contains("No matching DBC")));
        assertTrue(review.blockingIssues().isEmpty());
    }

    @Test
    void autoDecodeMatchingDbcStaysReadyWithoutNativeOnlyWarning() throws Exception {
        Path temp = Files.createTempDirectory("auto-with-dbc");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        WorkspaceFile dbc = addDefinition(service, temp, "vehicle.dbc");
        installRecommended(service, source, 1, dbc, 3, 3);

        DesktopAppService.DecisionProcessingReadiness readiness = service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(0, readiness.nativeOnlyChannels());
        assertEquals(1, readiness.dbcConfiguredChannels());

        service.enterProcessingWorkspaceFromDecision();
        ProcessingJob job = service.processingSelectionModel().reviewedPlan().jobs().getFirst();
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, job.mappingLifecycle());
        assertEquals(dbc.definitionSourceId().value(), job.definitionSourceId().orElseThrow());
        assertTrue(ProcessingReviewSummaryFactory.build(service).blockingIssues().isEmpty());
        assertEquals("Ready to process", ProcessingReviewSummaryFactory.build(service).readinessText());
    }

    @Test
    void autoDecodeDbcPresentButNoMatchIsWarningNotBlocker() throws Exception {
        Path temp = Files.createTempDirectory("auto-no-overlap");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        addDefinition(service, temp, "other.dbc");
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                null, "", null, "", 3, 0, 0.0));

        assertTrue(service.canProceedFromDecisionWorkspace());
        assertEquals(1, service.decisionProcessingReadiness().nativeOnlyChannels());
        service.enterProcessingWorkspaceFromDecision();
        ProcessingJob job = service.processingSelectionModel().reviewedPlan().jobs().getFirst();
        assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
        assertTrue(job.executable());
        assertTrue(job.definitionSourceId().isEmpty());
    }

    @Test
    void autoDecodeWithNoSelectedChannelsDisablesProceed() throws Exception {
        Path temp = Files.createTempDirectory("auto-none-selected");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        installNoDbc(service, source, 1, 3);
        service.quickDecodeSelectionModel().setIncluded(
                SourceChannelKey.of(source.projectSourceId(), 1), false);

        assertFalse(service.canProceedFromDecisionWorkspace());
        assertTrue(service.decisionProcessingHint().contains("Select at least one CAN channel"));
        service.enterProcessingWorkspaceFromDecision();
        assertTrue(service.processingSelectionModel().reviewedPlan().jobs().isEmpty());
    }

    @Test
    void inventoryWithNoCanChannelsRemainsBlocked() throws Exception {
        Path temp = Files.createTempDirectory("auto-no-can");
        DesktopAppService service = newService();
        addReadySource(service, temp, "empty.mf4", 0, 0L);

        assertFalse(service.canProceedFromDecisionWorkspace());
        assertEquals(0, service.decisionProcessingReadiness().requiredChannels());
    }

    @Test
    void customDecodeWithoutDbcProcessesNativeOnly() throws Exception {
        Path temp = Files.createTempDirectory("custom-no-dbc");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        installNoDbc(service, source, 1, 3);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);

        DesktopAppService.DecisionProcessingReadiness readiness = service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(1, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());
        assertEquals(0, readiness.dbcConfiguredChannels());
        service.enterProcessingWorkspaceFromDecision();
        ProcessingJob job = service.processingSelectionModel().reviewedPlan().jobs().getFirst();
        assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
        assertTrue(job.definitionSourceId().isEmpty());
        assertTrue(job.executable());
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    void customDecodeCompleteStillEnablesProceed() throws Exception {
        Path temp = Files.createTempDirectory("custom-complete");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        WorkspaceFile dbc = addDefinition(service, temp, "vehicle.dbc");
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(),
                3, 3, 1.0));
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);

        assertTrue(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        ProcessingJob job = service.processingSelectionModel().reviewedPlan().jobs().getFirst();
        assertEquals(MappingLifecycle.CONFIRMED, job.mappingLifecycle());
        assertTrue(job.executable());
    }

    @Test
    void changingDecodeStrategyDoesNotStartAProcessingJob() throws Exception {
        Path temp = Files.createTempDirectory("mode-toggle");
        DesktopAppService service = newService();
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 100L);
        installNoDbc(service, source, 1, 3);

        assertTrue(service.canProceedFromDecisionWorkspace());
        assertFalse(service.isPlanExecutionActive());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertTrue(service.canProceedFromDecisionWorkspace());
        assertEquals(1, service.decisionProcessingReadiness().nativeOnlyChannels());
        assertFalse(service.isPlanExecutionActive());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        assertTrue(service.canProceedFromDecisionWorkspace());
        assertFalse(service.isPlanExecutionActive());
        assertTrue(service.processingResultRegistry().all().isEmpty());
    }

    @Test
    void multiSourceAutoDecodeKeepsIndependentJobs() throws Exception {
        Path temp = Files.createTempDirectory("auto-multi");
        DesktopAppService service = newService();
        WorkspaceFile a = addReadySource(service, temp.resolve("left"), "same.mf4", 1, 10L);
        WorkspaceFile b = addReadySource(service, temp.resolve("right"), "same.mf4", 1, 20L);
        WorkspaceFile dbc = addDefinition(service, temp, "vehicle.dbc");
        installRecommended(service, a, 1, dbc, 1, 1);
        installNoDbc(service, b, 1, 1);

        assertTrue(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(2, plan.jobs().size());
        assertEquals(List.of(a.id(), b.id()), plan.jobs().stream().map(ProcessingJob::projectSourceId).toList());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, plan.jobs().getFirst().mappingLifecycle());
        assertEquals(MappingLifecycle.NO_MATCH, plan.jobs().get(1).mappingLifecycle());
        assertTrue(plan.jobs().stream().allMatch(ProcessingJob::executable));
    }

    private static DesktopAppService newService() {
        return new DesktopAppService(new DesktopSession(ParserRegistry.load()));
    }

    private static void installNoDbc(DesktopAppService service, WorkspaceFile source, int channelId, long observed) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), channelId),
                ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                null, "", null, "", observed, 0L, 0.0));
    }

    private static void installRecommended(
            DesktopAppService service, WorkspaceFile source, int channelId, WorkspaceFile dbc,
            long matched, long observed
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), channelId),
                ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(), null, "",
                observed, matched, observed == 0 ? 0.0 : matched / (double) observed));
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path dir, String name, int channels, long framesPerChannel
    ) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        java.util.ArrayList<CanChannelInventory> channelList = new java.util.ArrayList<>();
        for (int i = 1; i <= channels; i++) {
            channelList.add(new CanChannelInventory(
                    i, "Channel " + i, framesPerChannel, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false),
                            framesPerChannel, 8, 8, false))));
        }
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, framesPerChannel * Math.max(channels, 0), channels, true, channelList, List.of()));
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }
}
