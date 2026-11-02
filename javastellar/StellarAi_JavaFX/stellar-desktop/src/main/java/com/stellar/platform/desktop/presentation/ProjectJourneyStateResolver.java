package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.metadata.ProjectDashboardSummary;

/**
 * The single place that decides what state a project is in. Controllers must not re-derive it.
 */
public final class ProjectJourneyStateResolver {

    private ProjectJourneyStateResolver() {
    }

    public static ProjectJourneyState resolve(ProjectJourneyFacts facts) {
        return ProjectNextStepResolver.journeyState(facts);
    }

    /**
     * Projects Home rows for non-active projects use persisted aggregates only — never
     * hydration, file system probing or parsing.
     */
    public static ProjectJourneyFacts factsFrom(ProjectDashboardSummary summary) {
        if (summary == null) {
            return ProjectJourneyFacts.noProject();
        }
        if (!summary.countsAvailable()) {
            return new ProjectJourneyFacts(
                    true, 0, 0, false, false, false, false, false, false, false);
        }
        String lastStatus = summary.lastProcessingStatus();
        SourceWorkflowProfile profile = SourceWorkflowProfile.fromTypeLabel(summary.fileTypesLabel());
        boolean signals = summary.signalCount() > 0;
        boolean failed = ProcessingRunStatuses.isFailed(lastStatus);
        boolean completed = ProcessingRunStatuses.isCompleted(lastStatus) && !failed;
        boolean directPending = profile.hasDirect() && !signals && !completed && !failed;
        boolean settledEmpty = profile.directOnly() && !signals && completed;
        return new ProjectJourneyFacts(
                true,
                summary.measurementFileCount(),
                summary.measurementFileCount(),
                summary.mappingCount() > 0,
                false,
                ProcessingRunStatuses.isInterrupted(lastStatus),
                failed,
                signals,
                false,
                ProcessingRunStatuses.isCompletedWithWarnings(lastStatus),
                profile.directOnly(),
                directPending,
                settledEmpty);
    }

    public static ProjectJourneyState resolve(ProjectDashboardSummary summary) {
        if (summary != null && !summary.countsAvailable()) {
            return ProjectJourneyState.SOURCES_REQUIRED;
        }
        return resolve(factsFrom(summary));
    }
}
