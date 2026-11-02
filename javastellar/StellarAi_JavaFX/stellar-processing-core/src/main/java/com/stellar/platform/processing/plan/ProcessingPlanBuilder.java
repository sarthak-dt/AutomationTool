package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds an immutable {@link ProjectProcessingPlan} from current project metadata.
 * Invokes zero parser reads, inventory scans, DBC parses, or frame/signal decodes.
 */
public final class ProcessingPlanBuilder {

    public ProjectProcessingPlan build(ProjectChannelSelection selection, ProcessingPlanInputs inputs) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(inputs, "inputs");
        List<SourceChannelRef> selected = selection.selectedChannels();
        if (selected.isEmpty()) {
            return ProjectProcessingPlan.empty();
        }

        List<SourceChannelRef> ordered = order(selected, inputs);
        List<ProcessingJob> jobs = new ArrayList<>();
        List<ProcessingPlanDiagnostic> planDiagnostics = new ArrayList<>();

        for (SourceChannelRef channel : ordered) {
            jobs.add(buildJob(channel, inputs, planDiagnostics));
        }

        ProcessingPlanReadiness readiness = new ProcessingPlanValidator().classify(jobs);
        return new ProjectProcessingPlan(
                UUID.randomUUID().toString(),
                Instant.now(),
                jobs,
                readiness,
                planDiagnostics
        );
    }

    private static List<SourceChannelRef> order(List<SourceChannelRef> selected, ProcessingPlanInputs inputs) {
        return selected.stream()
                .sorted(Comparator
                        .comparingInt((SourceChannelRef ref) -> inputs.source(ref.projectSourceId())
                                .map(SourcePlanInput::sourceOrderIndex)
                                .orElse(Integer.MAX_VALUE))
                        .thenComparing(SourceChannelRef::projectSourceId)
                        .thenComparingInt(SourceChannelRef::channelId))
                .toList();
    }

    private static ProcessingJob buildJob(
            SourceChannelRef channel,
            ProcessingPlanInputs inputs,
            List<ProcessingPlanDiagnostic> planDiagnostics
    ) {
        List<ProcessingPlanDiagnostic> jobDiagnostics = new ArrayList<>();
        Optional<SourcePlanInput> sourceOpt = inputs.source(channel.projectSourceId());
        if (sourceOpt.isEmpty()) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.SOURCE_REMOVED, channel, "source not in project inputs");
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
            return blockedJob(channel, MappingLifecycle.UNMAPPED, 0, 0, 0, 0, 0.0, null, jobDiagnostics);
        }

        SourcePlanInput source = sourceOpt.get();
        CanSourceInventory inventory = source.inventory();
        if (inventory.status() != CanInventoryStatus.INVENTORY_READY) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.SOURCE_NOT_READY, channel, inventory.status().name());
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
        }

        Optional<CanChannelInventory> channelInv = inventory.channels().stream()
                .filter(ch -> ch.channelId() == channel.channelId())
                .findFirst();
        if (channelInv.isEmpty()) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.CHANNEL_NOT_AVAILABLE, channel, "channel missing from inventory");
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
            return blockedJob(
                    channel,
                    MappingLifecycle.UNMAPPED,
                    0, 0, 0, 0, 0.0,
                    source.observedTiming().orElse(null),
                    jobDiagnostics
            );
        }

        CanChannelInventory ch = channelInv.get();
        long frameCount = ch.frameCount();
        ChannelMappingView mapping = inputs.mapping(channel).orElse(null);
        MappingLifecycle lifecycle = mapping == null ? MappingLifecycle.UNMAPPED : mapping.lifecycle();

        long observed = mapping != null && mapping.observedUniqueMessageCount() > 0
                ? mapping.observedUniqueMessageCount()
                : ch.uniqueMessageCount();
        long matched = mapping == null ? 0L : mapping.matchedUniqueMessageCount();
        long unmatched = Math.max(0L, observed - matched);
        double coverage = mapping == null ? 0.0 : mapping.coverageRatio();
        long expectedMatchedFrames = mapping == null ? 0L : mapping.matchedFrameCount();
        double frameCoverage = mapping == null ? 0.0 : mapping.frameCoverageRatio();

        String definitionId = null;
        String definitionName = "";
        boolean definitionPresent = false;
        if (mapping != null) {
            definitionName = mapping.definitionDisplayName();
            definitionPresent = mapping.definitionPresent();
            if (lifecycle.executable() || lifecycle.previewOnly()) {
                definitionId = mapping.definitionSourceId().orElse(null);
            }
        }

        boolean executable = false;
        if (lifecycle.executable()) {
            if (definitionId == null || !definitionPresent) {
                ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE,
                        channel,
                        "executable mapping without available definition");
                jobDiagnostics.add(d);
                planDiagnostics.add(d);
            } else if (inventory.status() == CanInventoryStatus.INVENTORY_READY) {
                executable = true;
            }
        } else if (lifecycle.previewOnly()) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.UNCONFIRMED_DEFINITION,
                    channel,
                    "RECOMMENDED mapping is preview-only");
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
            if (definitionId == null || !definitionPresent) {
                ProcessingPlanDiagnostic missing = ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE, channel, "recommended definition missing");
                jobDiagnostics.add(missing);
                planDiagnostics.add(missing);
            }
        } else if (lifecycle == MappingLifecycle.AMBIGUOUS) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.AMBIGUOUS_DEFINITION, channel, "ambiguous mapping");
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
        } else if (lifecycle.allowsNativeOnlyProcessing()) {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION,
                    channel,
                    "no matching DBC; native/raw processing only");
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
            if (inventory.status() == CanInventoryStatus.INVENTORY_READY) {
                executable = true;
            }
        } else {
            ProcessingPlanDiagnostic d = ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION, channel, lifecycle.name());
            jobDiagnostics.add(d);
            planDiagnostics.add(d);
        }

        ObservedTimingMetadata timing = source.observedTiming().map(ObservedTimingMetadata::copy).orElse(null);
        return new ProcessingJob(
                ProcessingJobKey.of(channel),
                inventory.format(),
                lifecycle,
                definitionId,
                definitionName,
                observed,
                matched,
                unmatched,
                frameCount,
                coverage,
                expectedMatchedFrames,
                frameCoverage,
                timing,
                executable,
                jobDiagnostics
        );
    }

    private static ProcessingJob blockedJob(
            SourceChannelRef channel,
            MappingLifecycle lifecycle,
            long observed,
            long matched,
            long unmatched,
            long frames,
            double coverage,
            ObservedTimingMetadata timing,
            List<ProcessingPlanDiagnostic> diagnostics
    ) {
        return new ProcessingJob(
                ProcessingJobKey.of(channel),
                com.stellar.platform.domain.MeasurementSourceFormat.UNKNOWN,
                lifecycle,
                null,
                "",
                observed,
                matched,
                unmatched,
                frames,
                coverage,
                timing,
                false,
                diagnostics
        );
    }

}
