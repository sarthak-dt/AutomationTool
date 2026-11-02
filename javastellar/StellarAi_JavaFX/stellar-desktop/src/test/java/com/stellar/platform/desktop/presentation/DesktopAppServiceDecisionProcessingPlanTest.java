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
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppServiceDecisionProcessingPlanTest {

    @Test
    void mixedMappingStatesIncludeOnlyConfirmedAndManual() throws Exception {
        Path temp = Files.createTempDirectory("decision-plan-mixed");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile source = addReadySource(service, temp, "vehicle.mf4", 3, 100L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addRecommended(service, source, 1, definition.definitionSourceId(), 10);
        addConfirmed(service, source, 2, definition.definitionSourceId(), 20);
        addManual(service, source, 3, definition.definitionSourceId(), 30);

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(2, plan.jobs().size());
        Set<Integer> channels = plan.jobs().stream().map(ProcessingJob::channelId).collect(Collectors.toSet());
        assertEquals(Set.of(2, 3), channels);
        assertTrue(plan.jobs().stream().noneMatch(job -> job.channelId() == 1));
        assertTrue(plan.jobs().stream().allMatch(job -> job.mappingLifecycle().userAccepted()));
        assertEquals(source.id(), plan.jobs().get(0).projectSourceId());
        assertEquals(200L, plan.summary().selectedFrames());
        assertEquals(1, plan.summary().selectedSources());
        assertEquals(2, plan.summary().selectedChannels());
        assertEquals(2, plan.summary().processingJobs());
    }

    @Test
    void multiSourceExcludesRecommendedOnlyChannels() throws Exception {
        Path temp = Files.createTempDirectory("decision-plan-multi");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile sourceA = addReadySource(service, temp, "alpha.mf4", 3, 1_000L);
        WorkspaceFile sourceB = addReadySource(service, temp, "beta.mf4", 3, 2_000L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addRecommended(service, sourceA, 1, definition.definitionSourceId(), 10);
        addRecommended(service, sourceA, 2, definition.definitionSourceId(), 10);
        addConfirmed(service, sourceA, 3, definition.definitionSourceId(), 10);
        addRecommended(service, sourceB, 1, definition.definitionSourceId(), 10);
        addConfirmed(service, sourceB, 2, definition.definitionSourceId(), 10);
        addConfirmed(service, sourceB, 3, definition.definitionSourceId(), 10);

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(3, plan.jobs().size());
        assertEquals(2, plan.summary().selectedSources());
        assertEquals(3, plan.summary().selectedChannels());
        assertEquals(5_000L, plan.summary().selectedFrames());
        List<String> keys = plan.jobs().stream()
                .map(job -> job.projectSourceId() + ":" + job.channelId())
                .toList();
        assertEquals(List.of(sourceA.id() + ":3", sourceB.id() + ":2", sourceB.id() + ":3"), keys);
        assertTrue(plan.jobs().stream().noneMatch(job -> job.mappingLifecycle() == MappingLifecycle.RECOMMENDED
                || job.mappingLifecycle() == MappingLifecycle.RECOMMENDED_FALLBACK));
    }

    @Test
    void zeroConfirmedChannelsYieldEmptyPlanWithoutRecommendedFallback() throws Exception {
        Path temp = Files.createTempDirectory("decision-plan-empty");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile source = addReadySource(service, temp, "only-rec.mf4", 2, 500L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addRecommended(service, source, 1, definition.definitionSourceId(), 10);
        addRecommended(service, source, 2, definition.definitionSourceId(), 10);

        assertTrue(service.effectiveDbc(SourceChannelKey.of(source.projectSourceId(), 1)).resolved());
        assertFalse(service.effectiveDbc(SourceChannelKey.of(source.projectSourceId(), 1)).acceptedForProcessing());

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertTrue(plan.jobs().isEmpty());
        assertEquals(0, plan.summary().processingJobs());
        assertEquals(0L, plan.summary().selectedFrames());
        assertTrue(service.processingSelectionModel().selection().isEmpty());
    }

    @Test
    void duplicateFilenamesKeepIndependentSourceIdentities() throws Exception {
        Path temp = Files.createTempDirectory("decision-plan-dup");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile a = addReadySource(service, temp.resolve("left"), "same.mf4", 1, 10L);
        WorkspaceFile b = addReadySource(service, temp.resolve("right"), "same.mf4", 1, 20L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmed(service, a, 1, definition.definitionSourceId(), 4);
        addConfirmed(service, b, 1, definition.definitionSourceId(), 4);

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(2, plan.jobs().size());
        assertEquals(a.id(), plan.jobs().get(0).projectSourceId());
        assertEquals(b.id(), plan.jobs().get(1).projectSourceId());
        assertFalse(a.id().equals(b.id()));
        assertEquals("same.mf4", a.fileName());
        assertEquals("same.mf4", b.fileName());
        assertEquals(30L, plan.summary().selectedFrames());
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
            DesktopAppService service, WorkspaceFile measurement, int channelId, DefinitionSourceId defId, long observed
    ) {
        put(service, measurement, channelId, ChannelMappingStatus.CONFIRMED, defId, defId, observed);
    }

    private static void addManual(
            DesktopAppService service, WorkspaceFile measurement, int channelId, DefinitionSourceId defId, long observed
    ) {
        put(service, measurement, channelId, ChannelMappingStatus.MANUAL, defId, defId, observed);
    }

    private static void addRecommended(
            DesktopAppService service, WorkspaceFile measurement, int channelId, DefinitionSourceId defId, long observed
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.RECOMMENDED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "map.dbc", null, "", observed, observed, 1.0));
    }

    private static void put(
            DesktopAppService service,
            WorkspaceFile measurement,
            int channelId,
            ChannelMappingStatus status,
            DefinitionSourceId recommended,
            DefinitionSourceId mapped,
            long observed
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                status,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                recommended, "map.dbc", mapped, "map.dbc", observed, observed, 1.0));
    }
}
