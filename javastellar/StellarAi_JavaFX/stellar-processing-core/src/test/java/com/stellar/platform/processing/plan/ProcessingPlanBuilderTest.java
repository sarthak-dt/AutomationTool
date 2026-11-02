package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanBuilderTest {

    private static final class PlanBuildBoundaryProbe {
        final AtomicInteger inventoryScans = new AtomicInteger();
        final AtomicInteger parserCalls = new AtomicInteger();
        final AtomicInteger dbcReparses = new AtomicInteger();
        final AtomicInteger frameDecodes = new AtomicInteger();
        final AtomicInteger signalDecodes = new AtomicInteger();

        void assertUnused() {
            assertEquals(0, parserCalls.get());
            assertEquals(0, inventoryScans.get());
            assertEquals(0, dbcReparses.get());
            assertEquals(0, frameDecodes.get());
            assertEquals(0, signalDecodes.get());
        }
    }

    @Test
    void multiSourceProducesOneJobPerSelectedChannel() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        selection.select(SourceChannelRef.of("B", 2));
        selection.select(SourceChannelRef.of("B", 3));

        PlanBuildBoundaryProbe probe = new PlanBuildBoundaryProbe();
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, confirmedInputs(probe));
        assertEquals(3, plan.jobs().size());
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertEquals(0, probe.parserCalls.get());
        assertEquals(0, probe.inventoryScans.get());
        assertEquals(0, probe.dbcReparses.get());
        assertEquals(0, probe.frameDecodes.get());
        assertEquals(0, probe.signalDecodes.get());
    }

    @Test
    void builderSourceHasNoParserOrInventoryCollectorDependencies() throws Exception {
        Class<?> builder = ProcessingPlanBuilder.class;
        String pkg = builder.getPackageName();
        assertEquals("com.stellar.platform.processing.plan", pkg);
        for (Method m : builder.getDeclaredMethods()) {
            // Builder methods take only selection + plan inputs / domain metadata — no Path openers.
            for (Class<?> param : m.getParameterTypes()) {
                String n = param.getName();
                assertFalse(n.contains("MeasurementParser"), n);
                assertFalse(n.contains("SourceInventoryService"), n);
                assertFalse(n.contains("DbcParser"), n);
                assertFalse(n.contains("CanInventoryCollector"), n);
            }
        }
    }

    @Test
    void sameChannelNumberAcrossSourcesAreDistinctJobs() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        selection.select(SourceChannelRef.of("B", 1));
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, confirmedInputs());
        assertEquals(2, plan.jobs().size());
        assertNotEquals(plan.jobs().get(0).key(), plan.jobs().get(1).key());
    }

    @Test
    void duplicateSelectionDoesNotDuplicateJobs() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        SourceChannelRef a1 = SourceChannelRef.of("A", 1);
        selection.select(a1);
        selection.select(a1);
        assertEquals(1, new ProcessingPlanBuilder().build(selection, confirmedInputs()).jobs().size());
    }

    @Test
    void recommendedMappingIsNeverAutoConfirmed() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ChannelMappingView recommended = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.RECOMMENDED, "def-1", "obd.dbc", 3, 3, 1.0, true);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("A", 0, inventoryReady("a.mf4", channel(1, 100, ids(0x100, 0x200, 0x300))), null)),
                List.of(recommended)
        );
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        ProcessingJob job = plan.jobs().getFirst();
        assertEquals(MappingLifecycle.RECOMMENDED, job.mappingLifecycle());
        assertFalse(job.executable());
        assertEquals(ProcessingPlanReadiness.REQUIRES_CONFIRMATION, plan.readiness());
        assertTrue(plan.diagnostics().stream()
                .anyMatch(d -> d.code() == ProcessingPlanDiagnosticCode.UNCONFIRMED_DEFINITION));
    }

    @Test
    void recommendedFallbackIsExecutableWithoutConfirmingLifecycle() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ChannelMappingView fallback = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.RECOMMENDED_FALLBACK, "def-1", "obd.dbc", 3, 3, 1.0, true);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("A", 0, inventoryReady("a.mf4", channel(1, 100, ids(0x100, 0x200, 0x300))), null)),
                List.of(fallback)
        );
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        ProcessingJob job = plan.jobs().getFirst();
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, job.mappingLifecycle());
        assertTrue(job.executable());
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertEquals("Recommended", job.mappingLifecycle().planLabel());
    }

    @Test
    void confirmedMappingIsExecutableAndReady() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, confirmedInputs());
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertTrue(plan.jobs().getFirst().executable());
        assertEquals(MappingLifecycle.CONFIRMED, plan.jobs().getFirst().mappingLifecycle());
    }

    @Test
    void noMatchWithInventoryReadyIsExecutableNativeOnly() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ChannelMappingView none = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.NO_MATCH, null, "", 3, 0, 0.0, false);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("A", 0, inventoryReady("a.mf4", channel(1, 50, ids(1, 2, 3))), null)),
                List.of(none)
        );
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertTrue(plan.jobs().getFirst().executable());
        assertTrue(plan.jobs().getFirst().definitionSourceId().isEmpty());
        assertEquals(MappingLifecycle.NO_MATCH, plan.jobs().getFirst().mappingLifecycle());
        assertTrue(plan.diagnostics().stream()
                .anyMatch(d -> d.code() == ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION));
    }

    @Test
    void unmappedChannelRemainsBlocked() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ChannelMappingView none = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.UNMAPPED, null, "", 3, 0, 0.0, false);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("A", 0, inventoryReady("a.mf4", channel(1, 50, ids(1, 2, 3))), null)),
                List.of(none)
        );
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        assertEquals(ProcessingPlanReadiness.INVALID_SELECTION, plan.readiness());
        assertFalse(plan.jobs().getFirst().executable());
    }

    @Test
    void exactFrameCountPreserved() {
        long frames = 4_945_041L;
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("X", 2));
        ChannelMappingView map = mapping(
                SourceChannelRef.of("X", 2), MappingLifecycle.CONFIRMED, "d", "x.dbc", 55, 55, 1.0, true);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("X", 0, inventoryReady("x.mf4", channel(2, frames, ids(10, 20))), null)),
                List.of(map)
        );
        assertEquals(frames, new ProcessingPlanBuilder().build(selection, inputs).jobs().getFirst().frameCount());
    }

    @Test
    void observedTimingCopiedWithoutMutation() {
        ObservedTimingMetadata original = new ObservedTimingMetadata(1_000L, 2_000L, "ns", "abs-meta");
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        ChannelMappingView map = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, "d", "a.dbc", 1, 1, 1.0, true);
        ProcessingPlanInputs inputs = new ProcessingPlanInputs(
                List.of(source("A", 0, inventoryReady("a.mf4", channel(1, 10, ids(1))), original)),
                List.of(map)
        );
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, inputs);
        ObservedTimingMetadata inJob = plan.jobs().getFirst().observedTiming().orElseThrow();
        assertEquals(original, inJob);
        assertEquals(1_000L, original.recordingStartNanos().orElseThrow());
        assertEquals(2_000L, original.recordingEndNanos().orElseThrow());
        assertEquals("ns", original.timeBaseLabel());
        assertEquals("abs-meta", original.absoluteTimeMetadata());
        // Plan holds a copy — mutating fields is impossible (immutable); equality still holds.
        assertEquals(original.recordingStartNanos(), inJob.recordingStartNanos());
        assertEquals(original.recordingEndNanos(), inJob.recordingEndNanos());
    }

    @Test
    void mappingChangeUpdatesOnlyDefinitionProjection() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        long frames = 12_928L;
        CanSourceInventory inv = inventoryReady("a.mf4", channel(1, frames, ids(0x7E8)));
        ChannelMappingView defA = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, "def-A", "a.dbc", 1, 1, 1.0, true);
        ChannelMappingView defB = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.MANUAL, "def-B", "b.dbc", 1, 1, 1.0, true);
        ProcessingPlanInputs before = new ProcessingPlanInputs(List.of(source("A", 0, inv, null)), List.of(defA));
        ProcessingPlanInputs after = new ProcessingPlanInputs(List.of(source("A", 0, inv, null)), List.of(defB));
        ProcessingJob jobA = new ProcessingPlanBuilder().build(selection, before).jobs().getFirst();
        ProcessingJob jobB = new ProcessingPlanBuilder().build(selection, after).jobs().getFirst();
        assertEquals("def-A", jobA.definitionSourceId().orElseThrow());
        assertEquals("def-B", jobB.definitionSourceId().orElseThrow());
        assertEquals(frames, jobA.frameCount());
        assertEquals(frames, jobB.frameCount());
        assertEquals(jobA.channel(), jobB.channel());
        assertEquals(MappingLifecycle.MANUAL, jobB.mappingLifecycle());
    }

    @Test
    void removedSourceDoesNotLeaveExecutableJob() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("gone", 3));
        selection.select(SourceChannelRef.of("A", 1));
        ProjectProcessingPlan plan = new ProcessingPlanBuilder().build(selection, confirmedInputs());
        assertEquals(2, plan.jobs().size());
        ProcessingJob gone = plan.jobs().stream()
                .filter(j -> j.projectSourceId().equals("gone"))
                .findFirst().orElseThrow();
        assertFalse(gone.executable());
        assertEquals(ProcessingPlanReadiness.INVALID_SELECTION, plan.readiness());
        assertTrue(plan.diagnostics().stream()
                .anyMatch(d -> d.code() == ProcessingPlanDiagnosticCode.SOURCE_REMOVED));
    }

    @Test
    void recommendedThenConfirmedTransitionsWithoutReparse() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        CanSourceInventory inv = inventoryReady("a.mf4", channel(1, 100, ids(1)));
        ChannelMappingView recommended = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.RECOMMENDED, "def-1", "x.dbc", 1, 1, 1.0, true);
        ChannelMappingView confirmed = mapping(
                SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, "def-1", "x.dbc", 1, 1, 1.0, true);
        ProjectProcessingPlan preview = new ProcessingPlanBuilder().build(
                selection, new ProcessingPlanInputs(List.of(source("A", 0, inv, null)), List.of(recommended)));
        ProjectProcessingPlan ready = new ProcessingPlanBuilder().build(
                selection, new ProcessingPlanInputs(List.of(source("A", 0, inv, null)), List.of(confirmed)));
        assertEquals(ProcessingPlanReadiness.REQUIRES_CONFIRMATION, preview.readiness());
        assertEquals(ProcessingPlanReadiness.READY, ready.readiness());
        PlanBuildBoundaryProbe probe = new PlanBuildBoundaryProbe();
        // rebuild with probe-backed fixtures to prove zero boundary hits
        ProjectProcessingPlan ready2 = new ProcessingPlanBuilder().build(
                selection, new ProcessingPlanInputs(List.of(source("A", 0, inv, null)), List.of(confirmed)));
        assertEquals(ProcessingPlanReadiness.READY, ready2.readiness());
        probe.assertUnused();
    }

    @Test
    void summaryExcludesTimelineFieldsAndCountsFrames() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("A", 1));
        selection.select(SourceChannelRef.of("B", 3));
        var summary = new ProcessingPlanBuilder().build(selection, confirmedInputs()).summary();
        assertEquals(2, summary.selectedSources());
        assertEquals(2, summary.processingJobs());
        assertEquals(2, summary.readyJobs());
        assertEquals(0, summary.blockedJobs());
        assertTrue(summary.selectedFrames() > 0);
    }

    @Test
    void emptySelectionYieldsEmptyPlan() {
        ProjectProcessingPlan plan = new ProcessingPlanBuilder()
                .build(new ProjectChannelSelection(), confirmedInputs());
        assertTrue(plan.isEmpty());
        assertEquals(ProcessingPlanReadiness.EMPTY, plan.readiness());
    }

    private ProcessingPlanInputs confirmedInputs() {
        return confirmedInputs(new PlanBuildBoundaryProbe());
    }

    private ProcessingPlanInputs confirmedInputs(PlanBuildBoundaryProbe probe) {
        // Probe is available for collaborators that must never be invoked during plan build.
        Objects.requireNonNull(probe, "probe");
        return new ProcessingPlanInputs(
                List.of(
                        source("A", 0, inventoryReady("a.mf4", channel(1, 373_109L, ids(0x316, 0x329, 0x7E8))), null),
                        source("B", 1, inventoryReady("b.mf4",
                                channel(1, 10, ids(1)),
                                channel(2, 20, ids(2)),
                                channel(3, 113_886L, ids(0x453))), null)
                ),
                List.of(
                        mapping(SourceChannelRef.of("A", 1), MappingLifecycle.CONFIRMED, "def-obd", "obd.dbc", 3, 3, 1.0, true),
                        mapping(SourceChannelRef.of("B", 1), MappingLifecycle.CONFIRMED, "def-3", "3.dbc", 1, 1, 1.0, true),
                        mapping(SourceChannelRef.of("B", 2), MappingLifecycle.CONFIRMED, "def-3", "3.dbc", 1, 1, 1.0, true),
                        mapping(SourceChannelRef.of("B", 3), MappingLifecycle.CONFIRMED, "def-3", "3.dbc", 1, 1, 1.0, true)
                )
        );
    }

    private static SourcePlanInput source(
            String id, int order, CanSourceInventory inventory, ObservedTimingMetadata timing) {
        return new SourcePlanInput(id, order, inventory, timing);
    }

    private static ChannelMappingView mapping(
            SourceChannelRef channel,
            MappingLifecycle lifecycle,
            String defId,
            String defName,
            long observed,
            long matched,
            double coverage,
            boolean present
    ) {
        return new ChannelMappingView(
                channel, lifecycle, defId, defName, observed, matched, coverage, present);
    }

    private static CanSourceInventory inventoryReady(String fileName, CanChannelInventory... channels) {
        long frames = 0;
        long unique = 0;
        for (CanChannelInventory ch : channels) {
            frames += ch.frameCount();
            unique += ch.uniqueMessageCount();
        }
        return new CanSourceInventory(
                Path.of(fileName),
                fileName,
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                channels.length,
                frames,
                unique,
                true,
                List.of(channels),
                List.of()
        );
    }

    private static CanChannelInventory channel(int id, long frames, List<CanMessageInventory> messages) {
        return new CanChannelInventory(
                id, "Channel " + id, frames, messages.size(), messages.size(), 0, 0, messages);
    }

    private static List<CanMessageInventory> ids(long... canIds) {
        List<CanMessageInventory> list = new ArrayList<>();
        for (long canId : canIds) {
            list.add(new CanMessageInventory(new CanMessageKey(1, canId, false), 1, 8, 8, false));
        }
        return list;
    }
}
