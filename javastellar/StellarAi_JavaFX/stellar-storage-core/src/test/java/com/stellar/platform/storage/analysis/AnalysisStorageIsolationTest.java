package com.stellar.platform.storage.analysis;

import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageIsolationTest {

    @TempDir
    Path temp;

    @Test
    void isolateRedirectsHomeAwayFromOperatorDotStellarai() {
        Path operatorHome = Path.of(System.getProperty("user.home"), ".stellarai").toAbsolutePath().normalize();
        assertTrue(AnalysisStorageIsolation.enabled());
        assertNotEquals(operatorHome, StellarAiPaths.homeRoot());
        assertFalse(StellarAiPaths.homeRoot().startsWith(operatorHome));
    }

    @Test
    void defaultSettingsStoreDoesNotReadOperatorFile() {
        AnalysisStorageSettingsStore store = new AnalysisStorageSettingsStore();
        assertFalse(AnalysisStorageIsolation.isOperatorWorkspace(store.settingsFile()));
        assertTrue(store.loadUserSelectedRoot().isEmpty()
                || store.loadUserSelectedRoot().filter(root -> !AnalysisStorageIsolation.isOperatorWorkspace(root))
                .isPresent());
    }

    @Test
    void refuseOperatorCatalogDatabase() {
        Path operatorDb = AnalysisStorageIsolation.operatorConfiguredRoot()
                .map(root -> root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)
                        .resolve("database")
                        .resolve("stellar.db"))
                .orElse(Path.of(System.getProperty("user.home"), "Documents", "SignalFiles111",
                        "StellarAIAnalysis", "database", "stellar.db"));
        assertThrows(IllegalStateException.class, () -> new SqliteProjectMetadataStore(operatorDb));
    }

    @Test
    void refuseBindingOperatorAnalysisRoot() {
        Path operator = AnalysisStorageIsolation.operatorConfiguredRoot()
                .orElse(Path.of(System.getProperty("user.home"), "Documents", "SignalFiles111"));
        assertThrows(IllegalStateException.class, () -> AnalysisStorageRoots.setUserSelectedRoot(operator));
    }

    @Test
    void automatedRunRefusesCampaignHomesWithoutTouchingThem() throws Exception {
        assertTrue(AnalysisStorageIsolation.automatedJvm());
        Path database = Path.of("D:/StellarAI-E2E/Storage/StellarAIAnalysis/database/stellar.db");
        boolean present = Files.isRegularFile(database);
        long size = present ? Files.size(database) : -1L;
        FileTime modified = present ? Files.getLastModifiedTime(database) : null;
        Path config = Path.of("D:/config");
        FileTime configModified = Files.exists(config) ? Files.getLastModifiedTime(config) : null;
        assertThrows(IllegalStateException.class, () -> new SqliteProjectMetadataStore(database));
        assertThrows(IllegalStateException.class, () -> AnalysisStorageRoots.setUserSelectedRoot(config));
        if (present) {
            assertEquals(size, Files.size(database));
            assertEquals(modified, Files.getLastModifiedTime(database));
        }
        if (configModified != null) {
            assertEquals(configModified, Files.getLastModifiedTime(config));
        }
    }

    @Test
    void isolatedDatabaseCanBeOpened() throws Exception {
        Path db = temp.resolve("ok").resolve("stellar.db");
        Files.createDirectories(db.getParent());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(Files.isRegularFile(store.databasePath()));
            assertFalse(AnalysisStorageIsolation.isOperatorWorkspace(store.databasePath()));
        }
    }

    @Test
    void productionHomeIsOperatorWhenIsolationIsOff() {
        AnalysisStorageIsolation.runUnisolated(() -> {
            System.clearProperty("stellarai.home");
            System.clearProperty("jpackage.app-path");
            Path expected = Path.of(System.getProperty("user.home"), ".stellarai").toAbsolutePath().normalize();
            assertTrue(StellarAiPaths.homeRoot().equals(expected)
                    || StellarAiPaths.isPackagedLaunch());
        });
    }
}
