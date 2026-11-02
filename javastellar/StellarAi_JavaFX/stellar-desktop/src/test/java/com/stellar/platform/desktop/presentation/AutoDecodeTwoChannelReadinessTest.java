package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
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
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto Decode readiness for two-channel MF4 sources: channel 1 carries the DBC-decodable CAN
 * traffic, channel 2 has no overlap with any uploaded DBC.
 */
class AutoDecodeTwoChannelReadinessTest {

    private static final long OBD2_RESPONSE_ID = 2024L;

    @Test
    void matchedChannelPlusNoMatchChannelIsProcessableWithWarning() throws Exception {
        Fixture f = Fixture.create(1, "11_bit_OBD2 2.dbc");
        f.recommendClear(0, f.dbcs.getFirst());

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(2, readiness.requiredChannels());
        assertEquals(1, readiness.dbcConfiguredChannels());
        assertEquals(1, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());
        assertEquals(2, f.service.quickDecodeProcessableChannels().size());

        ProjectProcessingPlan plan = f.enterPlan();
        assertEquals(2, plan.jobs().size());
        ProcessingJob decode = job(plan, f.sources.getFirst(), 1);
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, decode.mappingLifecycle());
        assertEquals(f.dbcs.getFirst().definitionSourceId().value(), decode.definitionSourceId().orElseThrow());
        ProcessingJob nativeOnly = job(plan, f.sources.getFirst(), 2);
        assertEquals(MappingLifecycle.NO_MATCH, nativeOnly.mappingLifecycle());
        assertTrue(nativeOnly.definitionSourceId().isEmpty(), "No Match must never receive a DBC");
        assertTrue(plan.jobs().stream().allMatch(ProcessingJob::executable));

        ProcessingReviewSummary review = ProcessingReviewSummaryFactory.build(f.service);
        assertTrue(review.readyToProcess());
        assertEquals("Ready with warnings", review.readinessText());
        assertTrue(review.blockingIssues().isEmpty());
        assertTrue(review.issues().stream().anyMatch(issue ->
                issue.severity() == ReviewIssueSeverity.WARNING && issue.title().toLowerCase().contains("no matching dbc")));
    }

    @Test
    void eightSuchSourcesAreAllProcessableWithCorrectCounts() throws Exception {
        Fixture f = Fixture.create(8, "11_bit_OBD2 2.dbc");
        for (int i = 0; i < 8; i++) {
            f.recommendClear(i, f.dbcs.getFirst());
        }

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(16, readiness.requiredChannels());
        assertEquals(8, readiness.dbcConfiguredChannels());
        assertEquals(8, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());

        ProjectProcessingPlan plan = f.enterPlan();
        assertEquals(16, plan.jobs().size());
        assertEquals(8, plan.jobs().stream().filter(j -> j.definitionSourceId().isPresent()).count());
        assertTrue(plan.jobs().stream()
                .filter(j -> j.channelId() == 2)
                .allMatch(j -> j.mappingLifecycle() == MappingLifecycle.NO_MATCH && j.definitionSourceId().isEmpty()));
    }

    @Test
    void threeDbcsTiedOnTheSameCanIdAreAutoSelectedWithWarning() throws Exception {
        Fixture f = Fixture.create(8, "CSS-Electronics-OBD2-v1_4.dbc",
                "11_bit_OBD2 2.dbc", "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        for (int i = 0; i < 8; i++) {
            f.recommendAmbiguous(i);
        }
        WorkspaceFile listingOrderWinner = f.dbcs.stream()
                .filter(file -> file.fileName().equals("11_bit_OBD2 2.dbc"))
                .findFirst()
                .orElseThrow();

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed(), "Auto Decode must apply a deterministic DBC on an exact tie");
        assertEquals(16, readiness.requiredChannels());
        assertEquals(8, readiness.dbcConfiguredChannels());
        assertEquals(8, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());

        for (WorkspaceFile source : f.sources) {
            QuickDecodeDecisionResolver.Decision ch1 = f.decision(source, 1);
            assertEquals(QuickDecodeDecisionResolver.Status.READY, ch1.status());
            assertEquals(listingOrderWinner.definitionSourceId(), ch1.selected().definitionSourceId());
            assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, ch1.origin());
            assertTrue(ch1.autoSelectedWithWarning());
            assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, ch1.planLifecycle());
            assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, f.decision(source, 2).status());
            assertTrue(f.decision(source, 2).selected() == null
                    || f.decision(source, 2).selected().matchedUniqueMessageCount() <= 0L);
        }
        List<SourceChannelRef> processable = f.service.quickDecodeProcessableChannels();
        assertEquals(16, processable.size());

        ProjectProcessingPlan plan = f.enterPlan();
        assertEquals(16, plan.jobs().size());
        for (WorkspaceFile source : f.sources) {
            ProcessingJob decode = job(plan, source, 1);
            assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, decode.mappingLifecycle());
            assertEquals(listingOrderWinner.definitionSourceId().value(), decode.definitionSourceId().orElseThrow());
            ProcessingJob nativeOnly = job(plan, source, 2);
            assertEquals(MappingLifecycle.NO_MATCH, nativeOnly.mappingLifecycle());
            assertTrue(nativeOnly.definitionSourceId().isEmpty());
        }
    }

    @Test
    void choosingADbcPerAmbiguousChannelRestoresProcessingWithOnlyValidDecodeWork() throws Exception {
        Fixture f = Fixture.create(8, "11_bit_OBD2 2.dbc",
                "CSS-Electronics-OBD2-v1_4.dbc", "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        for (int i = 0; i < 8; i++) {
            f.recommendAmbiguous(i);
        }
        WorkspaceFile chosen = f.dbcs.getFirst();
        for (WorkspaceFile source : f.sources) {
            f.service.quickDecodeSelectionModel().setOverrides(
                    List.of(SourceChannelKey.of(source.projectSourceId(), 1),
                            SourceChannelKey.of(source.projectSourceId(), 2)),
                    chosen.definitionSourceId());
        }

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(8, readiness.dbcConfiguredChannels());
        assertEquals(8, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());

        ProjectProcessingPlan plan = f.enterPlan();
        assertEquals(16, plan.jobs().size());
        for (WorkspaceFile source : f.sources) {
            ProcessingJob decode = job(plan, source, 1);
            assertEquals(MappingLifecycle.MANUAL, decode.mappingLifecycle());
            assertEquals(chosen.definitionSourceId().value(), decode.definitionSourceId().orElseThrow());
            ProcessingJob nativeOnly = job(plan, source, 2);
            assertEquals(MappingLifecycle.NO_MATCH, nativeOnly.mappingLifecycle(),
                    "Override All must not force a zero-overlap DBC onto the no-match channel");
            assertTrue(nativeOnly.definitionSourceId().isEmpty());
        }
        assertTrue(ProcessingReviewSummaryFactory.build(f.service).issues().stream().anyMatch(issue ->
                issue.severity() == ReviewIssueSeverity.WARNING && issue.title().toLowerCase().contains("no matching dbc")));
    }

    @Test
    void customOverrideOfAutoSelectedTieIsManualAndLeavesNativeOnlyUntouched() throws Exception {
        Fixture f = Fixture.create(8, "CSS-Electronics-OBD2-v1_4.dbc",
                "11_bit_OBD2 2.dbc", "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        for (int i = 0; i < 8; i++) {
            f.recommendAmbiguous(i);
        }
        WorkspaceFile chosen = f.dbcs.stream()
                .filter(file -> file.fileName().equals("CSS-Electronics-OBD2-v1_4.dbc"))
                .findFirst()
                .orElseThrow();
        WorkspaceFile autoWinner = f.dbcs.stream()
                .filter(file -> file.fileName().equals("11_bit_OBD2 2.dbc"))
                .findFirst()
                .orElseThrow();

        assertTrue(f.service.canProceedFromDecisionWorkspace());
        assertEquals(0, f.service.compatibleReviewChannelCount(chosen.definitionSourceId()));

        f.service.quickDecodeSelectionModel().setOverride(
                SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 1), chosen.definitionSourceId());

        QuickDecodeDecisionResolver.Decision overridden = f.decision(f.sources.getFirst(), 1);
        assertEquals(QuickDecodeDecisionResolver.Status.READY, overridden.status());
        assertEquals(chosen.definitionSourceId(), overridden.selected().definitionSourceId());
        assertTrue(overridden.manualOverride());
        assertEquals(MappingLifecycle.MANUAL, overridden.planLifecycle());

        for (int i = 1; i < 8; i++) {
            QuickDecodeDecisionResolver.Decision ch1 = f.decision(f.sources.get(i), 1);
            assertEquals(autoWinner.definitionSourceId(), ch1.selected().definitionSourceId());
            assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, ch1.origin());
            assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, f.decision(f.sources.get(i), 2).status());
        }
        assertEquals(ChannelMappingStatus.NO_MATCH,
                f.service.channelMapping(SourceChannelKey.of(f.sources.getFirst().projectSourceId(), 2))
                        .orElseThrow().status());
        assertTrue(f.service.canProceedFromDecisionWorkspace());
    }

    @Test
    void customDecodeBulkApplyEnablesProcessingWithoutAssigningNoMatchChannels() throws Exception {
        Fixture f = Fixture.create(8, "11_bit_OBD2 2.dbc",
                "CSS-Electronics-OBD2-v1_4.dbc", "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc");
        for (int i = 0; i < 8; i++) {
            f.recommendAmbiguous(i);
        }
        f.service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        WorkspaceFile chosen = f.dbcs.getFirst();
        assertEquals(8, f.service.applySelectedDbcToCompatibleReviewChannels(chosen.definitionSourceId()));

        DesktopAppService.DecisionProcessingReadiness readiness = f.service.decisionProcessingReadiness();
        assertEquals(8, readiness.dbcConfiguredChannels());
        assertEquals(8, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.unresolvedChannels());
        assertTrue(readiness.canProceed());

        ProjectProcessingPlan plan = f.enterPlan();
        assertEquals(16, plan.jobs().size());
        assertTrue(plan.jobs().stream().allMatch(ProcessingJob::executable));
        for (WorkspaceFile source : f.sources) {
            ProcessingJob decode = job(plan, source, 1);
            assertEquals(MappingLifecycle.MANUAL, decode.mappingLifecycle());
            assertEquals(chosen.definitionSourceId().value(), decode.definitionSourceId().orElseThrow());
            ProcessingJob nativeOnly = job(plan, source, 2);
            assertEquals(MappingLifecycle.NO_MATCH, nativeOnly.mappingLifecycle());
            assertTrue(nativeOnly.definitionSourceId().isEmpty());
            assertTrue(f.service.channelMapping(SourceChannelKey.of(source.projectSourceId(), 2))
                    .orElseThrow().mappedDefinitionId().isEmpty());
        }
    }

    @Test
    void identicalIdEvidenceAcrossDbcsRanksAsAmbiguous() {
        SourceChannelKey key = SourceChannelKey.of(
                com.stellar.platform.desktop.project.ProjectSourceId.of("s"), 1);
        List<SourceChannelDefinitionCandidate> tied = List.of(
                candidate(key, "a", "11_bit_OBD2 2.dbc", 1),
                candidate(key, "b", "CSS-Electronics-OBD2-v1_4.dbc", 1),
                candidate(key, "c", "can1-11-bit-OBD2-isotp-DTCs-header-v4.0.dbc", 1));
        assertEquals(ChannelRecommendationKind.AMBIGUOUS, new RecommendationRankingPolicy().rank(tied).kind());
        assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH,
                new RecommendationRankingPolicy().rank(List.of(tied.getFirst())).kind());
    }

    private static ProcessingJob job(ProjectProcessingPlan plan, WorkspaceFile source, int channelId) {
        return plan.jobs().stream()
                .filter(j -> j.projectSourceId().equals(source.id()) && j.channelId() == channelId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing job " + source.fileName() + "#ch" + channelId));
    }

    private static SourceChannelDefinitionCandidate candidate(
            SourceChannelKey key, String id, String name, long matchedIds
    ) {
        long observedIds = 3L;
        long observedFrames = 1_000L;
        long matchedFrames = matchedIds > 0 ? 400L : 0L;
        return new SourceChannelDefinitionCandidate(
                key, com.stellar.platform.desktop.project.DefinitionSourceId.of(id), name, 100,
                matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observedIds, matchedIds, observedIds - matchedIds, matchedIds / (double) observedIds,
                observedFrames, matchedFrames, matchedFrames / (double) observedFrames);
    }

    private record Fixture(DesktopAppService service, List<WorkspaceFile> sources, List<WorkspaceFile> dbcs) {

        static Fixture create(int sourceCount, String... dbcNames) throws Exception {
            Path dir = Files.createTempDirectory("auto-two-channel");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            List<WorkspaceFile> sources = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                Path path = dir.resolve("drive-" + i + ".mf4");
                Files.writeString(path, "stub");
                WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
                service.workspace().addFile(file);
                List<CanChannelInventory> channels = List.of(channel(1, OBD2_RESPONSE_ID), channel(2, 0x300L));
                service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                        path, file.fileName(), MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                        2, 2_000L, 2, true, channels, List.of()));
                sources.add(file);
            }
            List<WorkspaceFile> dbcs = new ArrayList<>();
            for (String name : dbcNames) {
                Path path = dir.resolve(name);
                Files.writeString(path, "VERSION \"\"\n");
                WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
                service.workspace().addFile(file);
                dbcs.add(file);
            }
            Fixture fixture = new Fixture(service, List.copyOf(sources), List.copyOf(dbcs));
            fixture.installCandidates();
            for (int i = 0; i < sourceCount; i++) {
                fixture.noMatch(i);
            }
            return fixture;
        }

        private static CanChannelInventory channel(int id, long canId) {
            return new CanChannelInventory(id, "Channel " + id, 1_000L, 1, 1, 0, 0,
                    List.of(new CanMessageInventory(new CanMessageKey(id, canId, false), 1_000L, 8, 8, false)));
        }

        /** Every DBC overlaps channel 1 on the same single ID and never overlaps channel 2. */
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
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(sources.get(sourceIndex).projectSourceId(), 1),
                    ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 3, 1, 1 / 3.0));
        }

        void noMatch(int sourceIndex) {
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(sources.get(sourceIndex).projectSourceId(), 2),
                    ChannelMappingStatus.NO_MATCH, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 1, 0, 0.0));
        }

        QuickDecodeDecisionResolver.Decision decision(WorkspaceFile source, int channelId) {
            return new QuickDecodeDecisionResolver(service.definitionMatchingModel(), service.quickDecodeSelectionModel())
                    .resolve(SourceChannelKey.of(source.projectSourceId(), channelId));
        }

        ProjectProcessingPlan enterPlan() {
            service.enterProcessingWorkspaceFromDecision();
            return service.processingSelectionModel().reviewedPlan();
        }
    }
}
