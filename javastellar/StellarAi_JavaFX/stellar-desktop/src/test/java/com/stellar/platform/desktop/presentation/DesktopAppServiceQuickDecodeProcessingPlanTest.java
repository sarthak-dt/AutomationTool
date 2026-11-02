package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end plan contract for Quick Decode; no parser I/O is required to build the plan. */
class DesktopAppServiceQuickDecodeProcessingPlanTest {

    @Test
    void quickModeBuildsIndependentBestDbcPerChannelAndSkipsZeroMatchOverride() throws Exception {
        Path temp = Files.createTempDirectory("quick-plan-e2e");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 3, 1_000L);
        WorkspaceFile dbc1 = addDefinition(service, temp, "body.dbc");
        WorkspaceFile dbc2 = addDefinition(service, temp, "powertrain.dbc");

        installChannel(service, source, 1, dbc1, 9, 10, 900, 1_000,
                candidate(source, 1, dbc2, 2, 10, 100, 1_000));
        installChannel(service, source, 2, dbc2, 8, 10, 800, 1_000,
                candidate(source, 2, dbc1, 1, 10, 50, 1_000));
        installChannel(service, source, 3, dbc1, 10, 10, 1_000, 1_000,
                candidate(source, 3, dbc2, 0, 10, 0, 1_000));

        // Engineer intentionally applies an incompatible DBC to Ch3.
        SourceChannelKey ch3 = SourceChannelKey.of(source.projectSourceId(), 3);
        service.quickDecodeSelectionModel().setOverride(ch3, dbc2.definitionSourceId());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        service.enterProcessingWorkspaceFromDecision();

        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingSelectionModel().decisionOrigin());
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingDecisionMode());
        // Result provenance is a plan snapshot; changing the Decision Workspace preference later
        // must not reinterpret this Quick Decode plan as Detailed Mapping.
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingDecisionMode());
        assertEquals(3, plan.jobs().size());
        Map<Integer, ProcessingJob> byChannel = plan.jobs().stream()
                .collect(Collectors.toMap(ProcessingJob::channelId, Function.identity()));
        assertEquals(dbc1.definitionSourceId().value(), byChannel.get(1).definitionSourceId().orElseThrow());
        assertEquals(dbc2.definitionSourceId().value(), byChannel.get(2).definitionSourceId().orElseThrow());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, byChannel.get(1).mappingLifecycle());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, byChannel.get(2).mappingLifecycle());
        assertEquals(MappingLifecycle.NO_MATCH, byChannel.get(3).mappingLifecycle());
        assertTrue(byChannel.get(3).executable());
        assertTrue(byChannel.get(3).definitionSourceId().isEmpty());

        var rejected = service.quickDecodeDecisionResolver().resolve(ch3);
        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, rejected.status());
        assertEquals(dbc1.definitionSourceId(), rejected.recommendation().definitionSourceId());
        assertEquals(dbc2.definitionSourceId(), rejected.selected().definitionSourceId());
    }

    @Test
    void quickModeKeepsDuplicateFilenamesIndependentByProjectSourceId() throws Exception {
        Path temp = Files.createTempDirectory("quick-plan-duplicate-name");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile left = addReadySource(service, temp.resolve("left"), "same.mf4", 1, 10L);
        WorkspaceFile right = addReadySource(service, temp.resolve("right"), "same.mf4", 1, 20L);
        WorkspaceFile dbc = addDefinition(service, temp, "vehicle.dbc");
        installChannel(service, left, 1, dbc, 1, 1, 10, 10);
        installChannel(service, right, 1, dbc, 1, 1, 20, 20);

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        service.enterProcessingWorkspaceFromDecision();

        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(2, plan.jobs().size());
        assertEquals(List.of(left.id(), right.id()), plan.jobs().stream().map(ProcessingJob::projectSourceId).toList());
        assertEquals(30L, plan.summary().selectedFrames());
    }

    @Test
    void serviceDefaultIsQuickDecodeAndCustomIsExplicitOnly() {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingDecisionMode());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
        // A UI preference change alone must not rewrite provenance for an existing processing model.
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingDecisionMode());
    }

    private static void installChannel(
            DesktopAppService service,
            WorkspaceFile source,
            int channelId,
            WorkspaceFile recommendedDbc,
            long matchedIds,
            long observedIds,
            long matchedFrames,
            long observedFrames,
            SourceChannelDefinitionCandidate... alternatives
    ) {
        SourceChannelDefinitionCandidate best = candidate(
                source, channelId, recommendedDbc, matchedIds, observedIds, matchedFrames, observedFrames);
        List<SourceChannelDefinitionCandidate> all = new ArrayList<>(service.definitionMatchingModel().allCandidates());
        all.add(best);
        if (alternatives != null) {
            all.addAll(List.of(alternatives));
        }
        service.definitionMatchingModel().replaceCandidates(all);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                best.sourceChannelKey(), ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                best.definitionSourceId(), best.definitionDisplayName(), null, "",
                best.observedUniqueMessageCount(), best.matchedUniqueMessageCount(), best.coverageRatio()));
    }

    private static SourceChannelDefinitionCandidate candidate(
            WorkspaceFile source,
            int channelId,
            WorkspaceFile dbc,
            long matchedIds,
            long observedIds,
            long matchedFrames,
            long observedFrames
    ) {
        return new SourceChannelDefinitionCandidate(
                SourceChannelKey.of(source.projectSourceId(), channelId),
                dbc.definitionSourceId(), dbc.fileName(), 100,
                matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observedIds, matchedIds, Math.max(0L, observedIds - matchedIds),
                observedIds == 0 ? 0.0 : matchedIds / (double) observedIds,
                observedFrames, matchedFrames,
                observedFrames == 0 ? 0.0 : matchedFrames / (double) observedFrames);
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
                    List.of(new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false),
                            framesPerChannel, 8, 8, false))));
        }
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                channels, framesPerChannel * channels, channels, true, channelList, List.of()));
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
