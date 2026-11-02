package com.stellar.platform.desktop.presentation;

/**
 * The one authoritative answer to "where should this project open?".
 *
 * <p>The last explicit Save checkpoint is honoured when the project can use that workspace.
 * Transient processing routes are never restored from a saved page. An explicit Graphs
 * checkpoint is restored whenever analysis data is ready — missing graph membership degrades
 * inside Graphs, it does not reroute to Signals.</p>
 */
public final class ResumeDestinationResolver {

    private ResumeDestinationResolver() {
    }

    public static WorkspaceRoute resolve(
            ProjectJourneyState state,
            WorkspaceRoute savedWorkspace,
            ProjectJourneyFacts validity
    ) {
        if (validity != null && !validity.projectOpen()) {
            return WorkspaceRoute.HOME;
        }
        ProjectJourneyState effective = state == null ? ProjectJourneyState.NEW : state;
        if (savedWorkspace != null && resumable(savedWorkspace, effective, validity)) {
            return savedWorkspace;
        }
        return canonicalFor(effective, validity);
    }

    public static WorkspaceRoute canonicalFor(ProjectJourneyState state, ProjectJourneyFacts validity) {
        if (state == null) {
            return WorkspaceRoute.SOURCES;
        }
        return switch (state) {
            case NEW, SOURCES_REQUIRED, DIRECT_PROCESS, DIRECT_RESULT -> WorkspaceRoute.SOURCES;
            case DECODE_SETUP_REQUIRED -> WorkspaceRoute.DECODE;
            case READY_TO_PROCESS -> WorkspaceRoute.REVIEW_AND_PROCESS;
            case PROCESSING -> WorkspaceRoute.PROCESSING_RESUME;
            case PROCESSING_FAILED -> WorkspaceRoute.PROCESSING_RESULT;
            case ANALYSIS_READY, ANALYSIS_READY_WITH_WARNINGS -> graphContextValid(validity)
                    ? WorkspaceRoute.GRAPHS
                    : WorkspaceRoute.SIGNALS;
        };
    }

    private static boolean resumable(
            WorkspaceRoute saved, ProjectJourneyState state, ProjectJourneyFacts validity) {
        return switch (saved) {
            case SOURCES -> true;
            case DECODE -> ProjectNextStepResolver.savedDecodeResumable(validity);
            case SIGNALS -> state != null && state.analysisAvailable();
            case GRAPHS -> state != null && state.analysisAvailable();
            case INVESTIGATIONS -> state != null && state.analysisAvailable();
            // Projects Home / Configuration are not resume targets; processing routes are state-only.
            case PROJECTS_HOME, HOME, CONFIGURATION, REVIEW_AND_PROCESS, PROCESSING_RESUME,
                    PROCESSING_RESULT -> false;
        };
    }

    private static boolean graphContextValid(ProjectJourneyFacts validity) {
        return validity != null && validity.savedGraphContextValid();
    }
}
