package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductAuditEventTest {

    @TempDir
    Path temp;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void createSaveAndOpenWriteAuditEvents() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toString());
        Path db = temp.resolve("audit.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
        service.newProject("Audit Project", temp.resolve("project"));
        String id = service.currentProjectId();
        Path source = temp.resolve("a.asc");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalAsc(source);
        service.addMeasurementPaths(java.util.List.of(source), Runnable::run);
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        Set<AuditEventType> types = store.listRecentAuditEvents(id, 50).stream()
                .map(e -> e.eventType())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains(AuditEventType.PROJECT_CREATED), types.toString());
        assertTrue(types.contains(AuditEventType.PROJECT_SAVED), types.toString());
        assertTrue(types.contains(AuditEventType.PROJECT_OPENED), types.toString());
        assertTrue(types.contains(AuditEventType.SOURCE_ADDED), types.toString());
    }

    @Test
    void unsupportedSourceDoesNotEmitSourceAdded() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home-reject").toString());
        Path db = temp.resolve("audit-reject.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        service = new DesktopAppService(new DesktopSession(), store);
        service.newProject("Rejected Source", temp.resolve("project-reject"));
        String id = service.currentProjectId();
        Path source = temp.resolve("notes.asc");
        Files.writeString(source, "this is not an asc log\n");
        service.addMeasurementPaths(java.util.List.of(source), Runnable::run);
        assertTrue(service.workspace().measurementFiles().isEmpty());
        Set<AuditEventType> types = store.listRecentAuditEvents(id, 50).stream()
                .map(e -> e.eventType())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(!types.contains(AuditEventType.SOURCE_ADDED), types.toString());
    }
}
