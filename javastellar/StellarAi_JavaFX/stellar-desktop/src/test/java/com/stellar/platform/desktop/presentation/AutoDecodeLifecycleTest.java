package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Auto Decode ({@link DecisionWorkspaceMode#QUICK_DECODE}) is the project lifecycle default.
 * Custom Decode is entered only by explicit user action for the current session.
 */
class AutoDecodeLifecycleTest {

    @TempDir Path temp;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void newProjectDefaultsToQuickDecode() {
        service = newService();
        service.newProject("Fresh");
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingSelectionModel().decisionOrigin());
    }

    @Test
    void resetRuntimeReturnsToQuickDecodeAfterCustomSession() {
        service = newService();
        service.newProject("A");
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());

        service.newProject("B");
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.processingSelectionModel().decisionOrigin());
    }

    @Test
    void explicitCustomDecodeSetsDetailedMappingForCurrentSessionOnly() {
        service = newService();
        service.newProject("Session");
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
        service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.processingDecisionMode());
    }

    private DesktopAppService newService() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        return new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(temp.resolve("meta.db")));
    }
}
