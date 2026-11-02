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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto Decode must pick a deterministic DBC, Custom Decode remains the manual override,
 * and Reprocess Entire Project reuses surviving valid mappings without asking again.
 */
class AutoDecodeCustomReprocessLifecycleTest {

    private static final long OBD2_RESPONSE_ID = 2024L;

    @Test
    void clearBestMatchIsAutoProvenanceAndEnablesReviewAndProcess() throws Exception {
        Fixture f = Fixture.create(1, "11_bit_OBD2 2.dbc");
        f.recommendClear(0, f.dbcs.getFirst());

        QuickDecodeDecisionResolver.Decision ch1 = f.decision(f.sources.getFirst(), 1);
        assertEquals(QuickDecodeDecisionResolver.Status.READY, ch1.status());
        assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, ch1.origin());
        assertFalse(ch1.autoSelectedWithWarning());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, ch1.planLifecycle());
        assertEquals(f.dbcs.getFirst().definitionSourceId(), ch1.selected().definitionSourceId());

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(1, readiness.dbcConfiguredChannels());
        assertEquals(1, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());
        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, f.decision(f.sources.getFirst(), 2).status());
    }

    @Test
    void severalCandidatesUseEngineeringRankThenDocumentedTieBreak() throws Exception {
        Fixture f = Fixture.create(1, "Z-last.dbc", "11_bit_OBD2 2.dbc", "CSS-Electronics-OBD2-v1_4.dbc");
        f.recommendAmbiguous(0);

        QuickDecodeDecisionResolver.Decision ch1 = f.decision(f.sources.getFirst(), 1);
        assertEquals("11_bit_OBD2 2.dbc", ch1.selected().definitionDisplayName());
        assertTrue(ch1.autoSelectedWithWarning());
        assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, ch1.origin());
        assertTrue(f.service.canProceedFromDecisionWorkspace());
    }

    @Test
    void previouslyAppliedCompatibleDbcWinsAnExactTie() throws Exception {
        Fixture f = Fixture.create(1, "11_bit_OBD2 2.dbc", "CSS-Electronics-OBD2-v1_4.dbc");
        WorkspaceFile css = f.dbcs.get(1);
        f.service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.AMBIGUOUS,
                f.dbcs.getFirst().definitionSourceId(), f.dbcs.getFirst().fileName(),
                css.definitionSourceId(), css.fileName(), 3, 1, 1 / 3.0));

        QuickDecodeDecisionResolver.Decision ch1 = f.decision(f.sources.getFirst(), 1);
        assertEquals(css.definitionSourceId(), ch1.selected().definitionSourceId());
        assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, ch1.origin());
        assertTrue(f.service.canProceedFromDecisionWorkspace());
    }

    @Test
    void customDecodeOverrideIsManualAndNativeOnlyStaysUnmapped() throws Exception {
        Fixture f = Fixture.create(1, "11_bit_OBD2 2.dbc", "CSS-Electronics-OBD2-v1_4.dbc");
        f.recommendAmbiguous(0);
        WorkspaceFile override = f.dbcs.get(1);

        f.service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertFalse(f.service.canProceedFromDecisionWorkspace(),
                "Custom Decode still requires an explicit choice on an exact tie");
        f.service.chooseChannelDefinition(
                SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 1), override.definitionSourceId());

        assertEquals(ChannelMappingStatus.MANUAL,
                f.service.channelMapping(SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 1))
                        .orElseThrow().status());
        assertTrue(f.service.canProceedFromDecisionWorkspace());

        f.service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        QuickDecodeDecisionResolver.Decision autoView = f.decision(f.sources.getFirst(), 1);
        assertTrue(autoView.manualOverride());
        assertEquals(MappingLifecycle.MANUAL, autoView.planLifecycle());
        assertEquals(override.definitionSourceId(), autoView.selected().definitionSourceId());
        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, f.decision(f.sources.getFirst(), 2).status());
        assertTrue(f.service.channelMapping(SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 2))
                .orElseThrow().mappedDefinitionId().isEmpty());
    }

    @Test
    void reprocessReusesValidAutoMappingWithoutManualReconfirmation() throws Exception {
        Fixture f = Fixture.create(4, "CSS-Electronics-OBD2-v1_4.dbc", "11_bit_OBD2 2.dbc");
        for (int i = 0; i < 4; i++) {
            f.recommendAmbiguous(i);
        }
        WorkspaceFile autoWinner = f.dbcs.stream()
                .filter(file -> file.fileName().equals("11_bit_OBD2 2.dbc"))
                .findFirst()
                .orElseThrow();

        assertTrue(f.service.canProceedFromDecisionWorkspace());
        ProjectProcessingPlan first = f.enterPlan();
        assertEquals(8, first.jobs().size());
        String applied = first.jobs().stream()
                .filter(job -> job.channelId() == 1)
                .map(job -> job.definitionSourceId().orElseThrow())
                .findFirst()
                .orElseThrow();
        assertEquals(autoWinner.definitionSourceId().value(), applied);

        assertTrue(f.service.canProceedFromDecisionWorkspace(),
                "Reprocess must not demand a second manual DBC confirmation");
        ProjectProcessingPlan reprocess = f.service.rebuildActiveReprocessPlan();
        assertEquals(8, reprocess.jobs().size());
        assertEquals(4, reprocess.jobs().stream().map(ProcessingJob::projectSourceId).distinct().count());
        for (WorkspaceFile source : f.sources) {
            ProcessingJob decode = job(reprocess, source, 1);
            assertEquals(applied, decode.definitionSourceId().orElseThrow());
            assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, decode.mappingLifecycle());
            ProcessingJob nativeOnly = job(reprocess, source, 2);
            assertEquals(MappingLifecycle.NO_MATCH, nativeOnly.mappingLifecycle());
            assertTrue(nativeOnly.definitionSourceId().isEmpty());
        }
    }

    @Test
    void removedSourceIsAbsentAndNewSourceGetsAutoMapping() throws Exception {
        Fixture f = Fixture.create(4, "11_bit_OBD2 2.dbc", "CSS-Electronics-OBD2-v1_4.dbc");
        for (int i = 0; i < 4; i++) {
            f.recommendAmbiguous(i);
        }
        f.enterPlan();
        WorkspaceFile removed = f.sources.get(2);
        String removedId = removed.id();
        assertTrue(f.service.removeFile(removedId));

        ProjectProcessingPlan afterRemove = f.service.rebuildActiveReprocessPlan();
        Set<String> afterRemoveIds = afterRemove.jobs().stream()
                .map(ProcessingJob::projectSourceId)
                .collect(Collectors.toSet());
        assertEquals(6, afterRemove.jobs().size());
        assertFalse(afterRemoveIds.contains(removedId));
        assertTrue(f.service.canProceedFromDecisionWorkspace());

        WorkspaceFile added = f.addSource("drive-new.mf4");
        f.recommendAmbiguousFor(added);
        assertTrue(f.service.canProceedFromDecisionWorkspace());
        ProjectProcessingPlan afterAdd = f.service.rebuildActiveReprocessPlan();
        assertEquals(8, afterAdd.jobs().size());
        ProcessingJob newDecode = job(afterAdd, added, 1);
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, newDecode.mappingLifecycle());
        assertEquals("11_bit_OBD2 2.dbc", newDecode.definitionDisplayName());
        ProcessingJob newNative = job(afterAdd, added, 2);
        assertEquals(MappingLifecycle.NO_MATCH, newNative.mappingLifecycle());
        assertTrue(newNative.definitionSourceId().isEmpty());
        assertFalse(afterAdd.jobs().stream().anyMatch(job -> removedId.equals(job.projectSourceId())));
    }

    private static ProcessingJob job(ProjectProcessingPlan plan, WorkspaceFile source, int channelId) {
        return plan.jobs().stream()
                .filter(j -> j.projectSourceId().equals(source.id()) && j.channelId() == channelId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing job " + source.fileName() + "#ch" + channelId));
    }

    private record Fixture(DesktopAppService service, List<WorkspaceFile> sources, List<WorkspaceFile> dbcs, Path dir) {

        static Fixture create(int sourceCount, String... dbcNames) throws Exception {
            Path dir = Files.createTempDirectory("auto-reprocess");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            List<WorkspaceFile> sources = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                sources.add(addReadySource(service, dir, "drive-" + i + ".mf4"));
            }
            List<WorkspaceFile> dbcs = new ArrayList<>();
            for (String name : dbcNames) {
                Path path = dir.resolve(name);
                Files.writeString(path, "VERSION \"\"\n");
                WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
                service.workspace().addFile(file);
                dbcs.add(file);
            }
            Fixture fixture = new Fixture(service, sources, List.copyOf(dbcs), dir);
            fixture.installCandidates();
            for (WorkspaceFile source : sources) {
                fixture.noMatch(source);
            }
            return fixture;
        }

        WorkspaceFile addSource(String fileName) throws Exception {
            WorkspaceFile file = addReadySource(service, dir, fileName);
            sources.add(file);
            installCandidates();
            noMatch(file);
            return file;
        }

        private static WorkspaceFile addReadySource(DesktopAppService service, Path dir, String fileName)
                throws Exception {
            Path path = dir.resolve(fileName);
            Files.writeString(path, "stub");
            WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
            service.workspace().addFile(file);
            List<CanChannelInventory> channels = List.of(channel(1, OBD2_RESPONSE_ID), channel(2, 0x300L));
            service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                    path, file.fileName(), MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                    2, 2_000L, 2, true, channels, List.of()));
            return file;
        }

        private static CanChannelInventory channel(int id, long canId) {
            return new CanChannelInventory(id, "Channel " + id, 1_000L, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(id, canId, false), 1_000L, 8, 8, false)));
        }

        private void installCandidates() {
            List<SourceChannelDefinitionCandidate> all = new ArrayList<>();
            for (WorkspaceFile source : sources) {
                for (WorkspaceFile dbc : dbcs) {
                    all.add(candidate(SourceChannelKey.of(source.projectSourceId(), 1),
                            dbc.definitionSourceId().value(), dbc.fileName(), 1));
                    all.add(candidate(SourceChannelKey.of(source.projectSourceId(), 2),
                            dbc.definitionSourceId().value(), dbc.fileName(), 0));
                }
            }
            service.definitionMatchingModel().replaceCandidates(all);
        }

        void recommendClear(int sourceIndex, WorkspaceFile dbc) {
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(sources.get(sourceIndex).projectSourceId(), 1),
                    ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                    dbc.definitionSourceId(), dbc.fileName(), null, "", 3, 1, 1 / 3.0));
        }

        void recommendAmbiguous(int sourceIndex) {
            recommendAmbiguousFor(sources.get(sourceIndex));
        }

        void recommendAmbiguousFor(WorkspaceFile source) {
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(source.projectSourceId(), 1),
                    ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 3, 1, 1 / 3.0));
        }

        void noMatch(WorkspaceFile source) {
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(source.projectSourceId(), 2),
                    ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 1, 0, 0.0));
        }

        QuickDecodeDecisionResolver.Decision decision(WorkspaceFile source, int channelId) {
            return service.quickDecodeDecisionResolver()
                    .resolve(SourceChannelKey.of(source.projectSourceId(), channelId));
        }

        ProjectProcessingPlan enterPlan() {
            service.enterProcessingWorkspaceFromDecision();
            return service.processingSelectionModel().reviewedPlan();
        }
    }

    private static SourceChannelDefinitionCandidate candidate(
            SourceChannelKey key, String id, String name, long matchedIds
    ) {
        long observedIds = 3L;
        long observedFrames = 1_000L;
        long matchedFrames = matchedIds > 0 ? 400L : 0L;
        return new SourceChannelDefinitionCandidate(
                key, DefinitionSourceId.of(id), name, 100,
                matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observedIds, matchedIds, observedIds - matchedIds, matchedIds / (double) observedIds,
                observedFrames, matchedFrames, matchedFrames / (double) observedFrames);
    }
}
