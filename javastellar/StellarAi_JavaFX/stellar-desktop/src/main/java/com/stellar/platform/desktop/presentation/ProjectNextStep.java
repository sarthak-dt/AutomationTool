package com.stellar.platform.desktop.presentation;

/**
 * Authoritative next workflow step for a project. Navigation, workspace rows and sidebar
 * eligibility derive from this value. They do not re-check file extensions.
 */
public enum ProjectNextStep {
    ADD_SOURCES,
    ADD_DEFINITION,
    DIRECT_PROCESS,
    DIRECT_RESULT,
    DECODE_PROCESS,
    REVIEW_AND_PROCESS,
    PROCESSING,
    PROCESSING_RESULT,
    SIGNALS,
    GRAPHS,
    INVESTIGATION;

    /** True when the step sends the user into the DBC / decode workflow. */
    public boolean requestsDefinition() {
        return this == ADD_DEFINITION || this == DECODE_PROCESS;
    }

    public static ProjectNextStep from(ProjectJourneyState state, ProjectJourneyFacts facts) {
        ProjectJourneyState effective = state == null ? ProjectJourneyState.NEW : state;
        return switch (effective) {
            case NEW, SOURCES_REQUIRED -> ADD_SOURCES;
            case DIRECT_PROCESS -> DIRECT_PROCESS;
            case DIRECT_RESULT -> DIRECT_RESULT;
            case DECODE_SETUP_REQUIRED -> DECODE_PROCESS;
            case READY_TO_PROCESS -> REVIEW_AND_PROCESS;
            case PROCESSING -> PROCESSING;
            case PROCESSING_FAILED -> PROCESSING_RESULT;
            case ANALYSIS_READY, ANALYSIS_READY_WITH_WARNINGS -> downstream(facts);
        };
    }

    private static ProjectNextStep downstream(ProjectJourneyFacts facts) {
        if (facts != null && facts.savedGraphContextValid()) {
            return GRAPHS;
        }
        return SIGNALS;
    }
}
