package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.support.MainShellFxScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCreateOpenShellTest {

    @TempDir Path temp;

    @Test
    @Timeout(60)
    void newProjectFromTopToolbarCreatesProject() throws Exception {
        onFx("toolbar-create", (service, shell) -> {
            shell.setNewProjectNameForTest("Toolbar Project");
            shell.requestNewProject();
            assertTrue(service.isDurableNamedProject());
            assertEquals("Toolbar Project", service.workspace().projectName());
            assertTrue(service.savedProjects().stream().anyMatch(p -> "Toolbar Project".equals(p.name())));
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void newProjectFromProjectsHomeCreatesProject() throws Exception {
        onFx("home-create", (service, shell) -> {
            shell.setNewProjectNameForTest("Home Project");
            DashboardController dashboard = dashboard(shell);
            dashboard.startProjectFromHomeCard();
            assertTrue(service.isDurableNamedProject());
            assertEquals("Home Project", service.workspace().projectName());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void newProjectFromStartProjectCardUsesSameCreationPath() throws Exception {
        onFx("same-path", (service, shell) -> {
            shell.setNewProjectNameForTest("Shared Path");
            DashboardController dashboard = dashboard(shell);
            dashboard.startProjectFromHomeCard();
            String fromHome = service.currentProjectId();
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            service.closeProject();
            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
            shell.setNewProjectNameForTest("Shared Path Two");
            shell.requestNewProject();
            assertNotEquals(fromHome, service.currentProjectId());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            assertEquals(2, service.savedProjects().size());
        });
    }

    @Test
    @Timeout(60)
    void newProjectImmediatelyBecomesActive() throws Exception {
        onFx("active", (service, shell) -> {
            shell.setNewProjectNameForTest("Active Now");
            shell.requestNewProject();
            assertTrue(service.isDurableNamedProject());
            assertTrue(shell.projectNameChromeForTest().contains("Active Now"));
            assertEquals("Active Now", shell.statusProjectTextForTest());
            assertTrue(shell.sourcesNavVisibleForTest());
            assertTrue(shell.decodeNavVisibleForTest());
            assertTrue(shell.signalsNavVisibleForTest());
            assertTrue(shell.graphsNavVisibleForTest());
            assertTrue(shell.signalsNavDisabledForTest());
            assertTrue(shell.graphsNavDisabledForTest());
        });
    }

    @Test
    @Timeout(60)
    void analysisDataEnablesSignalsAndGraphs() throws Exception {
        onFx("analysis-nav", (service, shell) -> {
            shell.setNewProjectNameForTest("Analysis Nav");
            shell.requestNewProject();
            assertTrue(shell.signalsNavDisabledForTest());
            assertTrue(shell.graphsNavDisabledForTest());

            com.stellar.platform.processing.catalog.InMemorySignalCatalogStore store =
                    new com.stellar.platform.processing.catalog.InMemorySignalCatalogStore("capture.dat", "", 1_000);
            store.accept(java.util.List.of(new com.stellar.platform.domain.CanonicalSignal(
                    "Speed", "Speed", 1_000L, 0, 1, 1.5, 0L, "m/s", null,
                    com.stellar.platform.domain.SourceReference.ofLine(1))));
            service.signalsModel().replaceCatalog(store);
            shell.refreshProjectChrome();
            assertTrue(shell.signalsNavVisibleForTest());
            assertTrue(shell.graphsNavVisibleForTest());
            assertFalse(shell.signalsNavDisabledForTest());
            assertFalse(shell.graphsNavDisabledForTest());

            var key = service.signalsModel().catalog().entries().get(0).key();
            service.signalsModel().addToWorkspace(key);
            shell.refreshProjectChrome();
            assertTrue(service.projectJourneyFacts().savedGraphContextValid());
            assertFalse(shell.graphsNavDisabledForTest());
        });
    }

    @Test
    @Timeout(60)
    void newProjectRoutesDirectlyToSources() throws Exception {
        onFx("to-sources", (service, shell) -> {
            assertEquals(WorkspaceRoute.HOME, shell.visibleRoute());
            shell.setNewProjectNameForTest("Direct Sources");
            shell.requestNewProject();
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            assertEquals(NavigationPage.SOURCES, shell.visiblePage());
        });
    }

    @Test
    @Timeout(60)
    void newProjectDoesNotBounceToProjectsHome() throws Exception {
        onFx("no-bounce", (service, shell) -> {
            shell.setNewProjectNameForTest("No Bounce");
            shell.requestNewProject();
            assertNotEquals(WorkspaceRoute.PROJECTS_HOME, shell.visibleRoute());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void newProjectWithNoPriorActiveProjectDoesNotInvokeLeaveGuard() throws Exception {
        onFx("no-guard", (service, shell) -> {
            assertFalse(service.isDurableNamedProject());
            AtomicBoolean asked = new AtomicBoolean();
            shell.setUnsavedLeaveDecisionForTest(intent -> {
                asked.set(true);
                return UnsavedLeaveChoice.CANCEL;
            });
            shell.setStopProcessingDecisionForTest(label -> {
                asked.set(true);
                return false;
            });
            shell.setNewProjectNameForTest("No Guard");
            shell.requestNewProject();
            assertFalse(asked.get());
            assertTrue(service.isDurableNamedProject());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void newProjectInitializesSaveCoordinatorForNewProject() throws Exception {
        onFx("coord", (service, shell) -> {
            shell.setNewProjectNameForTest("Coord Shell");
            shell.requestNewProject();
            assertEquals(service.currentProjectId(), service.saveCoordinatorForTest().boundProjectId());
            assertEquals(com.stellar.platform.desktop.presentation.ProjectSaveState.CLEAN,
                    service.projectSaveState());
        });
    }

    @Test
    @Timeout(60)
    void validUniqueProjectNameIsAccepted() throws Exception {
        onFx("valid-name", (service, shell) -> {
            shell.setNewProjectNameForTest("  My Project  ");
            shell.requestNewProject();
            assertEquals("My Project", service.workspace().projectName());
            assertTrue(service.savedProjects().stream().anyMatch(p -> "My Project".equals(p.name())));
        });
    }

    @Test
    @Timeout(60)
    void creationFailureIsVisibleToUser() throws Exception {
        onFx("create-fail", (service, shell) -> {
            shell.setNewProjectNameForTest("First");
            shell.requestNewProject();
            shell.setNewProjectNameForTest("first");
            shell.requestNewProject();
            assertEquals("CREATE", shell.lastLifecycleErrorKindForTest());
            assertTrue(shell.lastLifecycleErrorForTest() != null
                    && !shell.lastLifecycleErrorForTest().isBlank());
            assertEquals("First", service.workspace().projectName());
        });
    }

    @Test
    @Timeout(60)
    void resumeProjectFromProjectsHomeOpensRequestedProject() throws Exception {
        onFx("resume-home", (service, shell) -> {
            shell.setNewProjectNameForTest("Resume Me");
            shell.requestNewProject();
            String id = service.currentProjectId();
            service.closeProject();
            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
            shell.requestOpenSavedProject(id);
            assertEquals(id, service.currentProjectId());
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
            assertTrue(shell.projectNameChromeForTest().contains("Resume Me"));
            assertEquals("Resume Me", shell.statusProjectTextForTest());
        });
    }

    @Test
    @Timeout(60)
    void openSavedProjectMakesRequestedProjectActive() throws Exception {
        onFx("open-active", (service, shell) -> {
            shell.setNewProjectNameForTest("Keep A");
            shell.requestNewProject();
            String a = service.currentProjectId();
            service.closeProject();
            shell.requestOpenSavedProject(a);
            assertTrue(service.isDurableNamedProject());
            assertEquals(a, service.currentProjectId());
            assertEquals("Keep A", service.workspace().projectName());
        });
    }

    @Test
    @Timeout(60)
    void openSavedEmptyProjectRoutesSources() throws Exception {
        onFx("open-empty", (service, shell) -> {
            shell.setNewProjectNameForTest("Empty Open");
            shell.requestNewProject();
            String id = service.currentProjectId();
            service.closeProject();
            shell.requestOpenSavedProject(id);
            assertEquals(WorkspaceRoute.SOURCES, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void openFailureIsVisibleToUser() throws Exception {
        onFx("open-fail", (service, shell) -> {
            shell.setNewProjectNameForTest("Will Fail Open");
            shell.requestNewProject();
            String id = service.currentProjectId();
            service.closeProject();
            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
            service.forceOpenHydrationFailureForTest(new IllegalStateException("hydration exploded"));
            shell.requestOpenSavedProject(id);
            assertEquals("OPEN", shell.lastLifecycleErrorKindForTest());
            assertTrue(shell.lastLifecycleErrorForTest() != null
                    && shell.lastLifecycleErrorForTest().contains("hydration exploded"));
            assertTrue(service.savedProjects().stream().anyMatch(p -> p.projectId().equals(id)));
            assertEquals(WorkspaceRoute.PROJECTS_HOME, shell.visibleRoute());
        });
    }

    @Test
    @Timeout(60)
    void failedOpenDoesNotDeleteSavedProject() throws Exception {
        onFx("open-keep", (service, shell) -> {
            shell.setNewProjectNameForTest("Keep After Fail");
            shell.requestNewProject();
            String id = service.currentProjectId();
            service.closeProject();
            service.forceOpenHydrationFailureForTest(new IllegalStateException("broken"));
            shell.requestOpenSavedProject(id);
            assertTrue(service.savedProjects().stream().anyMatch(p -> p.projectId().equals(id)));
            assertTrue(service.deleteProject(id).deleted());
            assertTrue(service.savedProjects().stream().noneMatch(p -> p.projectId().equals(id)));
        });
    }

    @Test
    @Timeout(60)
    void shellChromeMatchesActiveProjectAfterCreateAndOpen() throws Exception {
        onFx("chrome", (service, shell) -> {
            shell.setNewProjectNameForTest("Chrome Match");
            shell.requestNewProject();
            assertTrue(service.isDurableNamedProject());
            assertTrue(shell.projectNameChromeForTest().contains(service.workspace().projectName()));
            assertEquals(service.workspace().projectName(), shell.statusProjectTextForTest());
            String id = service.currentProjectId();
            service.closeProject();
            shell.requestOpenSavedProject(id);
            assertEquals(id, service.currentProjectId());
            assertTrue(shell.projectNameChromeForTest().contains("Chrome Match"));
            assertEquals("Chrome Match", shell.statusProjectTextForTest());
            assertFalse(shell.projectNameChromeForTest().contains("No active project"));
        });
    }

    private static DashboardController dashboard(MainShellController shell) {
        if (!shell.pageLoadedForTest(NavigationPage.PROJECTS_HOME)) {
            shell.openRoute(WorkspaceRoute.PROJECTS_HOME);
        }
        Object controller = shell.controllersForTest().get(NavigationPage.PROJECTS_HOME);
        assertTrue(controller instanceof DashboardController);
        return (DashboardController) controller;
    }

    private void onFx(String slug, MainShellFxScenario.ShellWork scenario) throws Exception {
        MainShellFxScenario.run(temp, slug, scenario);
    }
}
