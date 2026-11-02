package com.stellar.platform.desktop.presentation;

/**
 * Lightweight, already-resident facts used to resolve a project's journey state and resume
 * destination. Nothing here may read measurement files, run inventory, or query large signal
 * datasets — resume resolution must stay cheap enough to run on the JavaFX thread.
 */
public record ProjectJourneyFacts(
        boolean projectOpen,
        int sourceCount,
        int usableSourceCount,
        boolean decodeConfigurationComplete,
        boolean processingRunning,
        boolean processingInterrupted,
        boolean processingFailed,
        boolean analysisDataReady,
        boolean savedGraphContextValid,
        boolean processingCompletedWithWarnings,
        boolean directOnlyWorkflow,
        boolean directSourcePending,
        boolean directProcessingSettledWithoutSignals
) {
    private static final ProjectJourneyFacts NO_PROJECT =
            new ProjectJourneyFacts(false, 0, 0, false, false, false, false, false, false, false, false, false, false);

    public ProjectJourneyFacts {
        sourceCount = Math.max(0, sourceCount);
        usableSourceCount = Math.max(0, Math.min(sourceCount, usableSourceCount));
        // A graph workspace is only meaningful when the analysis data behind it is present.
        savedGraphContextValid = savedGraphContextValid && analysisDataReady;
        processingCompletedWithWarnings = processingCompletedWithWarnings && !processingFailed;
        directProcessingSettledWithoutSignals =
                directProcessingSettledWithoutSignals && directOnlyWorkflow && !analysisDataReady;
    }

    public ProjectJourneyFacts(
            boolean projectOpen,
            int sourceCount,
            int usableSourceCount,
            boolean decodeConfigurationComplete,
            boolean processingRunning,
            boolean processingInterrupted,
            boolean processingFailed,
            boolean analysisDataReady,
            boolean savedGraphContextValid
    ) {
        this(projectOpen, sourceCount, usableSourceCount, decodeConfigurationComplete,
                processingRunning, processingInterrupted, processingFailed, analysisDataReady,
                savedGraphContextValid, false, false, false, false);
    }

    public ProjectJourneyFacts(
            boolean projectOpen,
            int sourceCount,
            int usableSourceCount,
            boolean decodeConfigurationComplete,
            boolean processingRunning,
            boolean processingInterrupted,
            boolean processingFailed,
            boolean analysisDataReady,
            boolean savedGraphContextValid,
            boolean processingCompletedWithWarnings
    ) {
        this(projectOpen, sourceCount, usableSourceCount, decodeConfigurationComplete,
                processingRunning, processingInterrupted, processingFailed, analysisDataReady,
                savedGraphContextValid, processingCompletedWithWarnings, false, false, false);
    }

    public static ProjectJourneyFacts noProject() {
        return NO_PROJECT;
    }

    public boolean hasUsableSources() {
        return usableSourceCount > 0;
    }

    /** Decode & Process applies only when a definition-required source is part of the project. */
    public boolean decodeWorkspaceApplicable() {
        return hasUsableSources() && !directOnlyWorkflow;
    }
}
