package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.home.WorkspaceUiPreferencesStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After Configuration Save, StellarAI-owned data lives under the selected Analysis Storage.
 */
class AnalysisStorageLayoutTest {

    private static final int UNCHANGED_SCHEMA_VERSION = 21;

    private String previousHome;
    private String previousAuto;
    private String previousAppPath;
    private String previousIsolate;

    @AfterEach
    void restoreProcessState() {
        com.stellar.platform.desktop.DesktopLogging.closeForTests();
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousAuto);
        restore("jpackage.app-path", previousAppPath);
        restore(AnalysisStorageIsolation.PROPERTY, previousIsolate);
    }

    @Test
    void configuredRootCreatesCanonicalDirectoryLayout(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            Path controlled = selected.resolve("StellarAIAnalysis");
            assertEquals(controlled.toAbsolutePath().normalize(), AnalysisStorageRoots.controlledRoot().orElseThrow());
            assertTrue(Files.isDirectory(AnalysisStorageRoots.databaseRoot()));
            assertTrue(Files.isDirectory(AnalysisStorageRoots.projectsRoot()));
            assertTrue(Files.isDirectory(AnalysisStorageRoots.logsRoot()));
            assertTrue(Files.isDirectory(AnalysisStorageRoots.cacheRoot()));
            assertTrue(Files.isDirectory(AnalysisStorageRoots.tempRoot()));
            assertTrue(Files.isDirectory(AnalysisStorageRoots.configRoot()));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void databasePathIsUnderSelectedAnalysisStorage(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            Path expected = selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db")
                    .toAbsolutePath().normalize();
            assertEquals(expected, CentralSqlitePaths.databasePath());
            assertEquals(expected, service.activeDatabasePath().orElseThrow());
            assertTrue(Files.isRegularFile(expected));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void logsResolveUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        Path home = temp.resolve("home");
        DesktopAppService service = open(home);
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            DesktopLogging.bindConfiguredStorage();
            Path log = AnalysisStorageRoots.logsRoot().resolve("stellar-desktop.log");
            assertTrue(Files.isRegularFile(log));
            assertTrue(log.startsWith(selected.resolve("StellarAIAnalysis")));
            assertFalse(Files.exists(home.resolve("logs").resolve("stellar-desktop.log")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cacheResolvesUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            Path cache = AnalysisStorageRoots.cacheRoot();
            assertEquals(selected.resolve("StellarAIAnalysis").resolve("cache").toAbsolutePath().normalize(), cache);
            assertTrue(Files.isDirectory(cache));
            assertEquals(cache.resolve("maps"),
                    com.stellar.platform.desktop.ui.graph.map.OfflineMapPackRegistry.defaultMapsDirectory());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void tempResolvesUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            Path scratch = DesktopSession.localApiDataRoot();
            assertEquals(AnalysisStorageRoots.tempRoot().resolve("desktop-api"), scratch);
            assertTrue(scratch.startsWith(selected.resolve("StellarAIAnalysis").resolve("temp")));
            Path osDb = osTemp("stellar-desktop-api").resolve("stellar-api.db");
            var osDbStamp = Files.exists(osDb) ? Files.getLastModifiedTime(osDb) : null;
            MetadataStoreFactory factory = new MetadataStoreFactory(
                    Path.of(System.getProperty("java.io.tmpdir"), "stellar-desktop-api"));
            try {
                factory.store();
                assertTrue(Files.isRegularFile(scratch.resolve("stellar-api.db")));
            } finally {
                factory.close();
            }
            try (GraphSignalSessionStore graph = GraphSignalSessionStore.create("measurement", "definition")) {
                assertTrue(graph.root().startsWith(AnalysisStorageRoots.tempRoot().resolve("graph-sessions")));
                assertFalse(graph.root().startsWith(osTemp("stellarai")));
            }
            if (osDbStamp == null) {
                assertFalse(Files.exists(osDb));
            } else {
                assertEquals(osDbStamp, Files.getLastModifiedTime(osDb));
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void uiPreferencesResolveUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        Path home = temp.resolve("home");
        DesktopAppService service = open(home);
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            WorkspaceUiPreferencesStore store = new WorkspaceUiPreferencesStore();
            assertTrue(store.toggleFavorite("1001"));
            Path prefs = AnalysisStorageRoots.configRoot().resolve("workspace-ui.properties");
            assertTrue(Files.isRegularFile(prefs));
            assertTrue(prefs.startsWith(selected.resolve("StellarAIAnalysis").resolve("config")));
            assertFalse(Files.exists(home.resolve("config").resolve("workspace-ui.properties")));
            assertTrue(store.favoriteProjectIds().contains("1001"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void projectArtifactsResolveUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        Path home = temp.resolve("home");
        DesktopAppService service = open(home);
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            service.newProject("LayoutProject");
            String id = service.currentProjectId();
            Path artifacts = service.activeProjectRoot().orElseThrow();
            assertEquals(AnalysisStorageRoots.projectArtifactsRoot(id), artifacts);
            assertTrue(artifacts.startsWith(AnalysisStorageRoots.projectsRoot()));
            assertTrue(Files.isDirectory(artifacts));
            assertFalse(Files.exists(home.resolve("projects").resolve(id)));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void signalStoreResolvesUnderSelectedRoot(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
            service.newProject("SignalsLayout");
            Path signalStore = AnalysisStorageRoots.signalStoreRoot(service.currentProjectId());
            assertEquals(service.activeProjectRoot().orElseThrow().resolve("signal-store"), signalStore);
            assertTrue(signalStore.startsWith(selected.resolve("StellarAIAnalysis").resolve("projects")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void configuredStorageDoesNotCreateProjectDatabaseUnderApplicationHome(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path selected = temp.resolve("operator");
        DesktopAppService service = open(home);
        try {
            service.savedProjects();
            assertFalse(Files.exists(home.resolve("database").resolve("stellar.db")));
            assertTrue(service.saveAnalysisStorage(selected).valid());
            service.newProject("NoHomeDb");
            assertTrue(Files.isRegularFile(CentralSqlitePaths.databasePath()));
            assertFalse(Files.exists(home.resolve("database").resolve("stellar.db")));
            assertFalse(Files.exists(home.resolve("projects")));
            assertFalse(CentralSqlitePaths.databasePath().startsWith(home));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void configuredStorageDoesNotWriteRuntimeLogUnderApplicationHome(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        DesktopAppService service = open(home);
        try {
            assertTrue(service.saveAnalysisStorage(temp.resolve("operator")).valid());
            DesktopLogging.configureOnce();
            DesktopLogging.bindConfiguredStorage();
            assertFalse(Files.exists(home.resolve("logs").resolve("stellar-desktop.log")));
            assertTrue(Files.isRegularFile(AnalysisStorageRoots.logsRoot().resolve("stellar-desktop.log")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void configuredStorageDoesNotWriteStellarTempIntoOsTemp(@TempDir Path temp) throws Exception {
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(temp.resolve("operator")).valid());
            Path osApi = osTemp("stellar-desktop-api");
            Path osDb = osApi.resolve("stellar-api.db");
            var osDbStamp = Files.exists(osDb) ? Files.getLastModifiedTime(osDb) : null;
            assertFalse(DesktopSession.localApiDataRoot().startsWith(osApi));
            try (GraphSignalSessionStore graph = GraphSignalSessionStore.create("m", "d")) {
                assertTrue(graph.root().startsWith(AnalysisStorageRoots.tempRoot()));
                assertFalse(graph.root().startsWith(osTemp("stellarai")));
            }
            if (osDbStamp == null) {
                assertFalse(Files.exists(osDb));
            } else {
                assertEquals(osDbStamp, Files.getLastModifiedTime(osDb));
            }
            assertEquals(AnalysisStorageRoots.tempRoot().toString(),
                    System.getProperty(AnalysisStorageRoots.RUNTIME_TEMP_PROPERTY));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void restartRestoresTheSameCanonicalPaths(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        Path home = temp.resolve("home");
        DesktopAppService first = open(home);
        Path db;
        Path logs;
        Path cache;
        Path runtimeTemp;
        Path config;
        Path projects;
        try {
            assertTrue(first.saveAnalysisStorage(selected).valid());
            first.newProject("Restarted");
            db = CentralSqlitePaths.databasePath();
            logs = AnalysisStorageRoots.logsRoot();
            cache = AnalysisStorageRoots.cacheRoot();
            runtimeTemp = AnalysisStorageRoots.tempRoot();
            config = AnalysisStorageRoots.configRoot();
            projects = first.activeProjectRoot().orElseThrow();
        } finally {
            first.shutdown();
        }
        AnalysisStorageRoots.clear();
        DesktopAppService restarted = new DesktopAppService(new DesktopSession());
        try {
            assertEquals(selected.toAbsolutePath().normalize(), restarted.analysisStorage().userSelectedRoot().orElseThrow());
            assertEquals(db, CentralSqlitePaths.databasePath());
            assertEquals(logs, AnalysisStorageRoots.logsRoot());
            assertEquals(cache, AnalysisStorageRoots.cacheRoot());
            assertEquals(runtimeTemp, AnalysisStorageRoots.tempRoot());
            assertEquals(config, AnalysisStorageRoots.configRoot());
            assertEquals(projects, AnalysisStorageRoots.projectArtifactsRoot(restarted.savedProjects().stream()
                    .filter(p -> "Restarted".equals(p.name()))
                    .findFirst().orElseThrow().projectId()));
            assertTrue(restarted.savedProjects().stream().anyMatch(p -> "Restarted".equals(p.name())));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void unavailableConfiguredRootDoesNotFallBackToApplicationHome(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        installPackagedHome(home);
        Path blocked = Files.createFile(temp.resolve("not-a-directory"));
        Path bootstrap = home.resolve("config").resolve("analysis-storage.properties");
        Files.createDirectories(bootstrap.getParent());
        Properties properties = new Properties();
        properties.setProperty("analysis.storage.root", blocked.toAbsolutePath().normalize().toString());
        try (var out = Files.newOutputStream(bootstrap)) {
            properties.store(out, "test");
        }
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            assertTrue(service.savedProjects().isEmpty());
            assertTrue(service.activeDatabasePath().isEmpty());
            IllegalStateException failure = assertThrows(IllegalStateException.class, CentralSqlitePaths::ensureLayout);
            assertTrue(failure.getMessage().contains("unavailable") || failure.getMessage().contains("Analysis Storage"));
            assertFalse(Files.exists(home.resolve("database").resolve("stellar.db")));
            assertFalse(Files.exists(home.resolve("logs").resolve("stellar-desktop.log")));
            assertEquals(blocked.toAbsolutePath().normalize(), AnalysisStorageRoots.userSelectedRoot().orElseThrow());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void bootstrapPropertiesRemainReadable(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("operator");
        Path home = temp.resolve("home");
        DesktopAppService service = open(home);
        try {
            assertTrue(service.saveAnalysisStorage(selected).valid());
        } finally {
            service.shutdown();
        }
        Path bootstrap = home.resolve("config").resolve("analysis-storage.properties");
        assertTrue(Files.isRegularFile(bootstrap));
        Properties properties = new Properties();
        try (var in = Files.newInputStream(bootstrap)) {
            properties.load(in);
        }
        assertEquals(selected.toAbsolutePath().normalize().toString(), properties.getProperty("analysis.storage.root"));
        assertEquals(bootstrap, new AnalysisStorageSettingsStore().settingsFile());
        assertEquals(selected.toAbsolutePath().normalize(),
                new AnalysisStorageSettingsStore().loadUserSelectedRoot().orElseThrow());
    }

    @Test
    void schemaVersionIsUnchanged(@TempDir Path temp) throws Exception {
        DesktopAppService service = open(temp.resolve("home"));
        try {
            assertTrue(service.saveAnalysisStorage(temp.resolve("operator")).valid());
            assertEquals(UNCHANGED_SCHEMA_VERSION, SqliteProjectMetadataStore.currentSchemaVersion());
            assertTrue(service.activeDatabasePath().isPresent());
        } finally {
            service.shutdown();
        }
    }

    private DesktopAppService open(Path home) throws Exception {
        installPackagedHome(home);
        return new DesktopAppService(new DesktopSession());
    }

    private void installPackagedHome(Path home) throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        previousAppPath = System.getProperty("jpackage.app-path");
        previousIsolate = System.getProperty(AnalysisStorageIsolation.PROPERTY);
        Files.createDirectories(home);
        System.setProperty("stellarai.home", home.toAbsolutePath().normalize().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.setProperty("jpackage.app-path", "C:\\Program Files\\StellarAI\\StellarAI.exe");
        System.setProperty(AnalysisStorageIsolation.PROPERTY, "false");
        AnalysisStorageRoots.clear();
    }

    private static Path osTemp(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
