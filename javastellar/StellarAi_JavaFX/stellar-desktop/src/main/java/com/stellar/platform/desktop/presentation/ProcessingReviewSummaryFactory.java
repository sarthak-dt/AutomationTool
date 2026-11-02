package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProcessingPlanSummary;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Projects already-resident application state into the read-only Review &amp; Process summary.
 *
 * <p>This is a translation layer: it never validates anything itself. Blocking findings come from
 * the existing decision readiness and plan readiness semantics, so review and execution can never
 * disagree about whether the project can be processed.</p>
 */
public final class ProcessingReviewSummaryFactory {

    private ProcessingReviewSummaryFactory() {
    }

    public static ProcessingReviewSummary build(DesktopAppService appService) {
        if (appService == null) {
            return empty();
        }
        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        int usable = appService.usableDecodeSources().size();
        DecisionWorkspaceMode strategy = appService.decisionWorkspaceMode();

        ChannelTally tally = tallyChannels(appService, measurements, strategy);
        DesktopAppService.DecisionProcessingReadiness readiness = appService.decisionProcessingReadiness();
        ProjectProcessingPlan plan = appService.processingSelectionModel().reviewedPlan();
        ProcessingPlanSummary scope = plan == null ? null : plan.summary();

        int configured = readiness.dbcConfiguredChannels();
        List<ProcessingReviewSummary.ReviewIssue> issues =
                collectIssues(appService, measurements, usable, readiness, plan);

        return new ProcessingReviewSummary(
                measurements.size(),
                usable,
                tally.discovered(),
                strategy,
                appService.workspace().definitionFiles().size(),
                tally.included(),
                tally.ignored(),
                strategy == DecisionWorkspaceMode.QUICK_DECODE,
                configured,
                readiness.unresolvedChannels(),
                readiness.nativeOnlyChannels(),
                scope == null ? 0 : scope.processingJobs(),
                scope == null ? 0 : scope.selectedSources(),
                scope == null ? 0 : scope.selectedChannels(),
                issues);
    }

    private static ProcessingReviewSummary empty() {
        return new ProcessingReviewSummary(
                0, 0, 0, DecisionWorkspaceMode.QUICK_DECODE, 0, 0, 0, false, 0, 0, 0, 0, 0, 0,
                List.of(new ProcessingReviewSummary.ReviewIssue(
                        ReviewIssueSeverity.BLOCKING,
                        "No project is open",
                        "Open or create a project before processing.")));
    }

    private record ChannelTally(int discovered, int included, int ignored) {
    }

    /**
     * Auto Decode is the only strategy with real exclusion semantics, so an "ignored" count is
     * only produced there. Custom Decode reports every discovered channel as included rather
     * than inventing an exclusion concept the backend does not have.
     */
    private static ChannelTally tallyChannels(
            DesktopAppService appService,
            List<WorkspaceFile> measurements,
            DecisionWorkspaceMode strategy
    ) {
        int discovered = 0;
        int ignored = 0;
        boolean auto = strategy == DecisionWorkspaceMode.QUICK_DECODE;
        for (WorkspaceFile measurement : measurements) {
            Optional<CanSourceInventory> inventory =
                    appService.sourceInventoryModel().inventoryFor(measurement.id());
            if (inventory.isEmpty() || inventory.get().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (CanChannelInventory channel : inventory.get().channels()) {
                discovered++;
                if (!auto) {
                    continue;
                }
                SourceChannelKey key = SourceChannelKey.of(
                        measurement.projectSourceId(), channel.channelId());
                if (!appService.quickDecodeSelectionModel().included(key)) {
                    ignored++;
                }
            }
        }
        return new ChannelTally(discovered, discovered - ignored, ignored);
    }

    private static List<ProcessingReviewSummary.ReviewIssue> collectIssues(
            DesktopAppService appService,
            List<WorkspaceFile> measurements,
            int usableSources,
            DesktopAppService.DecisionProcessingReadiness readiness,
            ProjectProcessingPlan plan
    ) {
        List<ProcessingReviewSummary.ReviewIssue> issues = new ArrayList<>();

        if (usableSources == 0) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.BLOCKING,
                    "No usable measurement source",
                    measurements.isEmpty()
                            ? "Add at least one MF4, BLF or ASC source before processing."
                            : "None of the sources in this project can currently be read."));
        }

        for (WorkspaceFile measurement : measurements) {
            SourceStatus status = appService.sourceStatus(measurement);
            if (status == SourceStatus.UNAVAILABLE) {
                issues.add(new ProcessingReviewSummary.ReviewIssue(
                        ReviewIssueSeverity.BLOCKING,
                        "Source unavailable: " + measurement.fileName(),
                        "Locate the file or remove it from the project before processing."));
            } else if (status == SourceStatus.NEEDS_ATTENTION) {
                issues.add(new ProcessingReviewSummary.ReviewIssue(
                        ReviewIssueSeverity.WARNING,
                        "Source needs attention: " + measurement.fileName(),
                        "This source will be skipped unless it can be read."));
            }
        }

        if (!readiness.canProceed() && usableSources > 0) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.BLOCKING,
                    "Decode setup incomplete",
                    readiness.hint()));
        }

        if (plan == null || plan.jobs().isEmpty()) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.BLOCKING,
                    "Nothing to process",
                    "No channel in this project is currently configured for decoding."));
        } else if (plan.readiness() != ProcessingPlanReadiness.READY) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.BLOCKING,
                    "Processing scope is not ready",
                    "Resolve the outstanding decode configuration before processing."));
        } else if (!plan.jobs().stream().allMatch(ProcessingJob::executable)) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.BLOCKING,
                    "Some channels cannot be processed",
                    "Confirm a DBC for every channel included in the processing scope."));
        }

        if (readiness.nativeOnlyChannels() > 0 && readiness.canProceed()) {
            String title = readiness.nativeOnlyChannels() == readiness.requiredChannels()
                    ? "No matching DBC is available"
                    : readiness.nativeOnlyChannels() + " channel(s) have no matching DBC";
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.WARNING,
                    title,
                    "Processing can continue, but DBC-decoded CAN signals will not be generated."));
        }

        if (readiness.recommendedFallbackChannels() > 0) {
            issues.add(new ProcessingReviewSummary.ReviewIssue(
                    ReviewIssueSeverity.INFORMATION,
                    readiness.recommendedFallbackChannels() + " channel(s) use a recommended DBC",
                    readiness.readyTooltip()));
        }

        issues.addAll(changedSourceIssues(appService, measurements));
        return issues;
    }

    /**
     * A measurement whose fingerprint no longer matches its stored artifact is not reused: the
     * engine reprocesses it. Saying so up front is what stops the run looking like a silent
     * no-op on data the user changed outside StellarAI.
     */
    private static List<ProcessingReviewSummary.ReviewIssue> changedSourceIssues(
            DesktopAppService appService,
            List<WorkspaceFile> measurements
    ) {
        List<ProcessingReviewSummary.ReviewIssue> issues = new ArrayList<>();
        for (WorkspaceFile measurement : measurements) {
            ProjectSourceId sourceId = measurement.projectSourceId();
            if (appService.sourceChangedSinceLastProcessing(sourceId)) {
                issues.add(new ProcessingReviewSummary.ReviewIssue(
                        ReviewIssueSeverity.WARNING,
                        "Source changed since last processing: " + measurement.fileName(),
                        "Previous results for this source are no longer valid and it will be processed again."));
            }
        }
        return issues;
    }
}
