package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageServiceIsolationTest {

    @Test
    void defaultServiceDoesNotBindOperatorAnalysisStorage() {
        AnalysisStorageService service = new AnalysisStorageService();
        assertFalse(service.userSelectedRoot()
                .filter(AnalysisStorageIsolation::isOperatorWorkspace)
                .isPresent());
        assertFalse(AnalysisStorageRoots.userSelectedRoot()
                .filter(AnalysisStorageIsolation::isOperatorWorkspace)
                .isPresent());
        assertTrue(AnalysisStorageIsolation.enabled());
    }

    @Test
    void newProjectWritesOnlyIsolatedCatalog() {
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            service.newProject("Isolation Canary");
            assertTrue(service.activeDatabasePath().isPresent());
            assertFalse(service.activeDatabasePath()
                    .filter(AnalysisStorageIsolation::isOperatorWorkspace)
                    .isPresent());
            assertFalse(AnalysisStorageIsolation.isOperatorWorkspace(CentralSqlitePaths.databasePath()));
            assertTrue(service.saveProject().saved());
        } finally {
            service.shutdown();
        }
    }
}
