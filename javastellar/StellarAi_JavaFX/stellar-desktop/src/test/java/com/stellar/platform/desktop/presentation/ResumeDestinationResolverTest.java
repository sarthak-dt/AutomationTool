package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resume honours the last explicit Save checkpoint when the project can use that
 * workspace. Transient processing routes are never restored from a saved page.
 */
class ResumeDestinationResolverTest {

    private static ProjectJourneyFacts facts(
            int sources,
            int usableSources,
            boolean decodeComplete,
            boolean running,
            boolean interrupted,
            boolean failed,
            boolean analysisReady,
            boolean graphContextValid
    ) {
        return new ProjectJourneyFacts(true, sources, usableSources, decodeComplete,
                running, interrupted, failed, analysisReady, graphContextValid);
    }

    private static WorkspaceRoute resume(ProjectJourneyFacts f, WorkspaceRoute saved) {
        return ResumeDestinationResolver.resolve(ProjectJourneyStateResolver.resolve(f), saved, f);
    }

    @Test
    void newProjectOpensSources() {
        ProjectJourneyFacts f = facts(0, 0, false, false, false, false, false, false);
        assertEquals(ProjectJourneyState.NEW, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.SOURCES, resume(f, null));
    }

    @Test
    void savedEmptyProjectOpensSources() {
        ProjectJourneyFacts f = facts(0, 0, false, false, false, false, false, false);
        assertEquals(WorkspaceRoute.SOURCES, resume(f, WorkspaceRoute.SOURCES));
    }

    @Test
    void projectWithUnusableSourcesRequiresSources() {
        ProjectJourneyFacts f = facts(3, 0, true, false, false, false, false, false);
        assertEquals(ProjectJourneyState.SOURCES_REQUIRED, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.SOURCES, resume(f, WorkspaceRoute.DECODE));
    }

    @Test
    void projectWithSourcesAndIncompleteDecodeOpensDecode() {
        ProjectJourneyFacts f = facts(2, 2, false, false, false, false, false, false);
        assertEquals(ProjectJourneyState.DECODE_SETUP_REQUIRED, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.DECODE, resume(f, null));
    }

    @Test
    void readyProjectOpensReviewAndProcess() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, false, false);
        assertEquals(ProjectJourneyState.READY_TO_PROCESS, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS, resume(f, null));
    }

    @Test
    void interruptedProcessingResumesProcessing() {
        ProjectJourneyFacts f = facts(2, 2, true, false, true, false, false, false);
        assertEquals(ProjectJourneyState.PROCESSING, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.PROCESSING_RESUME, resume(f, WorkspaceRoute.SIGNALS));
    }

    @Test
    void liveProcessingResumesProcessing() {
        ProjectJourneyFacts f = facts(2, 2, true, true, false, false, false, false);
        assertEquals(ProjectJourneyState.PROCESSING, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.PROCESSING_RESUME, resume(f, null));
    }

    @Test
    void failedProcessingOpensProcessingResult() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, true, false, false);
        assertEquals(ProjectJourneyState.PROCESSING_FAILED, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.PROCESSING_RESULT, resume(f, WorkspaceRoute.GRAPHS));
    }

    @Test
    void analysisReadyProjectOpensSignals() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, true, false);
        assertEquals(ProjectJourneyState.ANALYSIS_READY, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.SIGNALS, resume(f, null));
    }

    @Test
    void analysisReadyProjectWithValidSavedGraphContextOpensGraphs() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, true, true);
        assertEquals(WorkspaceRoute.GRAPHS, resume(f, WorkspaceRoute.GRAPHS));
        assertEquals(WorkspaceRoute.GRAPHS, resume(f, null));
    }

    @Test
    void explicitSavedGraphsResumesGraphsWhenAnalysisReady() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, true, false);
        assertEquals(WorkspaceRoute.GRAPHS, resume(f, WorkspaceRoute.GRAPHS));
    }

    @Test
    void savedGraphsPageIsIgnoredWhenNoProcessingResultExists() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, false, true);
        assertNotEquals(WorkspaceRoute.GRAPHS, resume(f, WorkspaceRoute.GRAPHS));
        assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS, resume(f, WorkspaceRoute.GRAPHS));
    }

    @Test
    void savedSignalsPageIsIgnoredWhenAnalysisIsNotReady() {
        ProjectJourneyFacts f = facts(2, 2, false, false, false, false, false, false);
        assertEquals(WorkspaceRoute.DECODE, resume(f, WorkspaceRoute.SIGNALS));
    }

    @Test
    void transientProcessingRoutesAreNeverRestoredFromASavedPage() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, true, false);
        for (WorkspaceRoute transientRoute : new WorkspaceRoute[]{
                WorkspaceRoute.REVIEW_AND_PROCESS,
                WorkspaceRoute.PROCESSING_RESUME,
                WorkspaceRoute.PROCESSING_RESULT}) {
            assertEquals(WorkspaceRoute.SIGNALS, resume(f, transientRoute));
        }
    }

    @Test
    void closedProjectResolvesToHome() {
        ProjectJourneyFacts f = ProjectJourneyFacts.noProject();
        assertEquals(WorkspaceRoute.HOME,
                ResumeDestinationResolver.resolve(ProjectJourneyState.ANALYSIS_READY, WorkspaceRoute.GRAPHS, f));
    }

    @Test
    void graphContextCannotBeValidWithoutAnalysisData() {
        ProjectJourneyFacts f = facts(2, 2, true, false, false, false, false, true);
        assertTrue(!f.savedGraphContextValid());
    }

    @Test
    void decodeIncompleteBeatsInterruptedRunBecausePlanCannotSafelyResume() {
        ProjectJourneyFacts f = facts(2, 2, false, false, true, false, false, false);
        assertEquals(ProjectJourneyState.DECODE_SETUP_REQUIRED, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.DECODE, resume(f, WorkspaceRoute.PROCESSING_RESUME));
    }

    @Test
    void missingSourcesWithValidAnalysisResumeAnalysis() {
        ProjectJourneyFacts f = facts(1, 0, true, false, false, false, true, false);
        assertEquals(ProjectJourneyState.ANALYSIS_READY, ProjectJourneyStateResolver.resolve(f));
        assertEquals(WorkspaceRoute.SIGNALS, resume(f, null));
    }

    @Test
    void emptyProjectResumesSources() {
        ProjectJourneyFacts f = facts(0, 0, false, false, false, false, false, false);
        assertEquals(WorkspaceRoute.SOURCES, resume(f, WorkspaceRoute.GRAPHS));
    }

    @Test
    void readyProjectResumesReview() {
        readyProjectOpensReviewAndProcess();
    }

    @Test
    void cancelledRunResumesProcessing() {
        interruptedProcessingResumesProcessing();
    }

    @Test
    void failedRunResumesProcessingResult() {
        failedProcessingOpensProcessingResult();
    }

    @Test
    void analysisReadyWithoutGraphResumesSignals() {
        analysisReadyProjectOpensSignals();
    }

    @Test
    void analysisReadyWithValidGraphResumesGraphs() {
        analysisReadyProjectWithValidSavedGraphContextOpensGraphs();
    }

    @Test
    void invalidGraphStateStillHonorsExplicitGraphsCheckpoint() {
        explicitSavedGraphsResumesGraphsWhenAnalysisReady();
    }

    @Test
    void savedTransientRouteIsNotBlindlyRestored() {
        transientProcessingRoutesAreNeverRestoredFromASavedPage();
    }
}
