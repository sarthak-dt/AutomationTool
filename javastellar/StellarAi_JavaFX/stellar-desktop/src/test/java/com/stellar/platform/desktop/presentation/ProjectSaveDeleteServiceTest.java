package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSaveDeleteServiceTest {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private SqliteProjectMetadataStore store;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    private void openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        System.setProperty("stellarai.metadata.db", tempDir.resolve("meta.db").toString());
        store = new SqliteProjectMetadataStore(tempDir.resolve("meta.db"));
        service = new DesktopAppService(new DesktopSession(), store);
    }

    @Test
    void newProjectRejectsDuplicateNames() {
        openService();
        service.newProject("Road Test");
        assertThrows(DuplicateProjectNameException.class, () -> service.newProject("road test"));
        assertThrows(DuplicateProjectNameException.class, () -> service.newProject("  Road Test  "));
        service.newProject("Road Test 2");
        assertEquals(2, service.savedProjects().size());
    }

    @Test
    void saveThenReopenPreservesProjectIdAndName() {
        openService();
        service.newProject("Durability Test");
        String id = service.currentProjectId();
        DesktopAppService.ProjectSaveResult saved = service.saveProject();
        assertTrue(saved.saved());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());

        service.shutdown();
        openService();
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject(id);
        assertTrue(opened.opened());
        assertEquals("Durability Test", service.workspace().projectName());
        assertEquals(id, service.currentProjectId());
        assertEquals(ProjectSaveState.CLEAN, service.projectSaveState());
    }

    @Test
    void deleteActiveProjectClearsRuntimeAndRemovesArtifacts() throws Exception {
        openService();
        service.newProject("Active Delete");
        String id = service.currentProjectId();
        Path managed = StellarAiPaths.projectRoot(id);
        Files.createDirectories(managed.resolve("signal-store"));
        Files.writeString(managed.resolve("signal-store").resolve("x.bin"), "data");

        DesktopAppService.ProjectDeleteResult deleted = service.deleteProject(id);
        assertTrue(deleted.deleted());
        assertTrue(service.savedProjects().stream().noneMatch(p -> p.projectId().equals(id)));
        assertFalse(Files.exists(managed));
        assertTrue(service.signalsModel().catalogStore() == null
                || service.signalsModel().catalog().entries().isEmpty());
    }

    @Test
    void saveUpdateDoesNotDuplicateProjectRow() {
        openService();
        service.newProject("Keep One");
        String id = service.currentProjectId();
        service.saveProject();
        service.renameProject("Keep One Updated");
        service.saveProject();
        assertEquals(1, service.savedProjects().size());
        assertEquals(id, service.savedProjects().getFirst().projectId());
        assertEquals("Keep One Updated", service.savedProjects().getFirst().name());
    }
}
