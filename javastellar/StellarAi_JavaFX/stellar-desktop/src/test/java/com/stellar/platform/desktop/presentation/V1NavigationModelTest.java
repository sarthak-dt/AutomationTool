package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the frozen V1 navigation model against the six-step technical workflow creeping back in.
 */
class V1NavigationModelTest {

    @Test
    void permanentNavigationIsExactlySourcesDecodeSignalsGraphsInvestigations() {
        assertEquals(
                List.of(NavigationPage.SOURCES, NavigationPage.DECODE,
                        NavigationPage.SIGNALS, NavigationPage.GRAPHS, NavigationPage.INVESTIGATIONS),
                NavigationPage.permanentProjectPages());
    }

    @Test
    void processingIsNotPermanentNavigation() {
        assertFalse(NavigationPage.PROCESSING.permanentProjectNavigation());
        assertFalse(NavigationPage.permanentProjectPages().contains(NavigationPage.PROCESSING));
        assertEquals(NavigationGroup.TRANSIENT, NavigationPage.PROCESSING.group());
    }

    @Test
    void homeIsTheWorkspaceLauncherAndMustNotBeNamedDashboard() {
        assertEquals("Workspace", NavigationPage.HOME.label());
        assertFalse(hasPageNamed("DASHBOARD"));
        assertEquals(NavigationGroup.HOME, NavigationPage.HOME.group());
        assertEquals(WorkspaceRoute.HOME, WorkspaceRoute.forPage(NavigationPage.HOME));
    }

    @Test
    void figmaSampleModulesAreNotNavigationDestinations() {
        for (String retired : List.of("UNAVAILABLE", "DAQ", "REPORTS", "AUTOMATION",
                "COMPUTE_SERVER", "REPORT_DESIGNER")) {
            assertFalse(hasPageNamed(retired), retired + " must not be a NavigationPage");
        }
        assertFalse(hasPageLabelled("daq"));
        assertFalse(hasPageLabelled("report"));
        assertFalse(hasPageLabelled("automation"));
        for (WorkspaceRoute route : WorkspaceRoute.values()) {
            assertFalse(route.name().contains("DAQ"));
            assertFalse(route.name().contains("REPORT"));
            assertFalse(route.name().contains("AUTOMATION"));
            assertFalse(route.name().contains("COMPUTE"));
        }
    }

    @Test
    void projectsHomeIsNotPartOfTheOpenProjectWorkspace() {
        assertFalse(NavigationPage.PROJECTS_HOME.permanentProjectNavigation());
        assertEquals(NavigationGroup.HOME, NavigationPage.PROJECTS_HOME.group());
    }

    @Test
    void inventoryIsNotTopLevelNavigation() {
        assertFalse(hasPageNamed("INVENTORY"));
        assertFalse(hasPageLabelled("inventory"));
    }

    @Test
    void dbcMappingIsNotTopLevelNavigation() {
        assertFalse(hasPageNamed("MAPPING"));
        assertFalse(hasPageNamed("DBC_MAPPING"));
        assertFalse(hasPageLabelled("mapping"));
        assertFalse(hasPageLabelled("dbc"));
    }

    @Test
    void studyAndReviewAreNotTopLevelNavigation() {
        assertFalse(hasPageNamed("STUDY"));
        assertFalse(hasPageLabelled("study"));
        assertFalse(hasPageLabelled("review"));
    }

    @Test
    void sixStepTechnicalStagesAreNoLongerNavigationDestinations() {
        for (String retired : List.of("INGEST", "INVENTORY", "MAPPING",
                "PROCESSING_PLAN", "EXECUTION", "DASHBOARD", "PROJECT_FILES",
                "GRAPH", "DIAGNOSTICS", "TESTS", "SETTINGS")) {
            assertFalse(hasPageNamed(retired), retired + " must not be a NavigationPage");
        }
    }

    @Test
    void analysisLabelIsPresentedAsGraphs() {
        assertEquals("Graphs", NavigationPage.GRAPHS.label());
        assertFalse(hasPageLabelled("analysis"));
    }

    @Test
    void everyTransientRouteResolvesToTheProcessingPage() {
        assertEquals(NavigationPage.PROCESSING, WorkspaceRoute.REVIEW_AND_PROCESS.page());
        assertEquals(NavigationPage.PROCESSING, WorkspaceRoute.PROCESSING_RESUME.page());
        assertEquals(NavigationPage.PROCESSING, WorkspaceRoute.PROCESSING_RESULT.page());
        assertTrue(WorkspaceRoute.REVIEW_AND_PROCESS.transientProcessingRoute());
        assertTrue(WorkspaceRoute.PROCESSING_RESUME.transientProcessingRoute());
        assertTrue(WorkspaceRoute.PROCESSING_RESULT.transientProcessingRoute());
    }

    @Test
    void everyPermanentPageHasAStableRoute() {
        for (NavigationPage page : NavigationPage.permanentProjectPages()) {
            WorkspaceRoute route = WorkspaceRoute.forPage(page);
            assertEquals(page, route.page());
            assertFalse(route.transientProcessingRoute(), page + " must not be a transient route");
        }
    }

    @Test
    void userFacingStatusesAreTheApprovedLabels() {
        assertEquals(
                List.of("Setup", "Ready to Process", "Processing", "Ready", "Ready with warnings",
                        "Failed", "Needs attention"),
                List.of(ProjectStatusLabel.SETUP.display(),
                        ProjectStatusLabel.READY_TO_PROCESS.display(),
                        ProjectStatusLabel.PROCESSING.display(),
                        ProjectStatusLabel.ANALYSIS_READY.display(),
                        ProjectStatusLabel.ANALYSIS_READY_WITH_WARNINGS.display(),
                        ProjectStatusLabel.FAILED.display(),
                        ProjectStatusLabel.ACTION_REQUIRED.display()));
        assertEquals(7, ProjectStatusLabel.values().length);
        assertFalse(ProjectStatusLabel.ANALYSIS_READY_WITH_WARNINGS.display().contains("COMPLETED"));
    }

    @Test
    void internalJourneyStateNamesAreNeverExposedToUsers() {
        for (ProjectJourneyState state : ProjectJourneyState.values()) {
            String status = state.statusLabel().display();
            assertFalse(status.contains("_"), state + " leaks an internal state name");
            assertFalse(status.equals(state.name()), state + " leaks an internal state name");
            assertFalse(state.primaryActionLabel().isBlank(), state + " has no user-facing action");
        }
    }

    private static boolean hasPageNamed(String name) {
        for (NavigationPage page : NavigationPage.values()) {
            if (page.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPageLabelled(String fragment) {
        for (NavigationPage page : NavigationPage.values()) {
            if (page.label().toLowerCase(Locale.ROOT).contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
