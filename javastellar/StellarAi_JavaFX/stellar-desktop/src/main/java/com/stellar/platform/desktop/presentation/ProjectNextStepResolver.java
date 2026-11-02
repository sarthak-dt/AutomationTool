package com.stellar.platform.desktop.presentation;

/**
 * The one place that decides a project's next workflow step.
 *
 * <p>DAT and LOG are direct-processing sources. A missing DBC is not a decode step for them.
 * MF4, ASC and BLF keep the existing definition workflow. A mixed project does not inherit
 * Decode &amp; Process from one source when another source can be processed directly, and
 * signals from a direct source stay reachable while a definition source is still incomplete.</p>
 */
public final class ProjectNextStepResolver {

    private ProjectNextStepResolver() {
    }

    public static ProjectJourneyState journeyState(ProjectJourneyFacts facts) {
        if (facts == null || !facts.projectOpen()) {
            return ProjectJourneyState.NEW;
        }
        if (facts.processingRunning()) {
            return ProjectJourneyState.PROCESSING;
        }
        if (!facts.hasUsableSources()) {
            if (facts.analysisDataReady()) {
                return readyState(facts);
            }
            boolean untouched = facts.sourceCount() == 0;
            return untouched ? ProjectJourneyState.NEW : ProjectJourneyState.SOURCES_REQUIRED;
        }
        if (facts.directOnlyWorkflow()) {
            return directOnlyState(facts);
        }
        if (facts.directSourcePending()
                && !facts.analysisDataReady()
                && !facts.processingInterrupted()
                && !facts.processingFailed()) {
            return ProjectJourneyState.DIRECT_PROCESS;
        }
        if (!facts.decodeConfigurationComplete()) {
            if (facts.analysisDataReady()) {
                return readyState(facts);
            }
            return ProjectJourneyState.DECODE_SETUP_REQUIRED;
        }
        if (facts.processingInterrupted()) {
            return ProjectJourneyState.PROCESSING;
        }
        if (facts.processingFailed()) {
            return ProjectJourneyState.PROCESSING_FAILED;
        }
        if (facts.analysisDataReady()) {
            return readyState(facts);
        }
        return ProjectJourneyState.READY_TO_PROCESS;
    }

    public static ProjectNextStep nextStep(ProjectJourneyFacts facts) {
        return ProjectNextStep.from(journeyState(facts), facts);
    }

    /**
     * A saved Decode checkpoint is a real resume target only for a definition workflow that
     * still needs it. Direct-only projects, and mixed projects that still have an unprocessed
     * direct source, must not be pulled back onto Decode &amp; Process.
     */
    public static boolean savedDecodeResumable(ProjectJourneyFacts facts) {
        return facts != null
                && facts.hasUsableSources()
                && !facts.directOnlyWorkflow()
                && !facts.directSourcePending();
    }

    private static ProjectJourneyState directOnlyState(ProjectJourneyFacts facts) {
        if (facts.processingInterrupted()) {
            return ProjectJourneyState.PROCESSING;
        }
        if (facts.processingFailed()) {
            return ProjectJourneyState.PROCESSING_FAILED;
        }
        if (facts.analysisDataReady()) {
            return readyState(facts);
        }
        if (facts.directProcessingSettledWithoutSignals()) {
            return ProjectJourneyState.DIRECT_RESULT;
        }
        return ProjectJourneyState.DIRECT_PROCESS;
    }

    private static ProjectJourneyState readyState(ProjectJourneyFacts facts) {
        return ProjectJourneyState.ANALYSIS_READY;
    }
}
