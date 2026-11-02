package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.analysis.AnalysisStorageIsolation;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageValidator;
import com.stellar.platform.storage.analysis.CentralSqlitePaths;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The folder chosen in Configuration is the project database location.
 * The application-home / packaged cache directory is not.
 */
class ConfiguredPersistentStorageTest {

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
    void saveCreatesCanonicalDatabaseAndSurvivesRestart(@TempDir Path temp) throws Exception {
        Path cacheHome = temp.resolve("installed-home");
        Path selected = temp.resolve("Stellar Data").resolve("项目");
        installPackagedHome(cacheHome);

        DesktopAppService first = new DesktopAppService(new DesktopSession());
        try {
            first.savedProjects();
            assertTrue(first.activeDatabasePath().isEmpty());
            assertFalse(Files.exists(cacheHome.resolve("database").resolve("stellar.db")));

            AnalysisStorageValidator.Result saved = first.saveAnalysisStorage(selected);
            assertTrue(saved.valid(), saved.message());
            Path configuredDb = selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db");
            assertEquals(configuredDb, first.activeDatabasePath().orElseThrow());
            assertTrue(Files.isRegularFile(configuredDb));
            assertEquals(selected.toAbsolutePath().normalize(), first.analysisStorage().userSelectedRoot().orElseThrow());

            first.newProject("StoragePathTest");
            assertTrue(names(first).contains("StoragePathTest"));
            assertFalse(Files.exists(cacheHome.resolve("database").resolve("stellar.db")));
            assertEquals(StellarAiPaths.homeRoot(), cacheHome.toAbsolutePath().normalize());
            assertTrue(StellarAiPaths.configRoot().startsWith(cacheHome));
            assertFalse(CentralSqlitePaths.databasePath().startsWith(cacheHome));
        } finally {
            first.shutdown();
        }

        DesktopAppService restarted = new DesktopAppService(new DesktopSession());
        try {
            assertEquals(selected.toAbsolutePath().normalize(), restarted.analysisStorage().userSelectedRoot().orElseThrow());
            Path configuredDb = selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db");
            assertTrue(restarted.savedProjects().stream().anyMatch(p -> "StoragePathTest".equals(p.name())));
            assertEquals(configuredDb, restarted.activeDatabasePath().orElseThrow());
            assertTrue(SqliteProjectMetadataStore.currentSchemaVersion() > 0);
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void missingDirectoryIsCreatedAndExistingDirectoryIsReused(@TempDir Path temp) throws Exception {
        installPackagedHome(temp.resolve("home"));
        Path missing = temp.resolve("missing-storage");
        assertFalse(Files.exists(missing));
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            AnalysisStorageValidator.Result created = service.saveAnalysisStorage(missing);
            assertTrue(created.valid(), created.message());
            Path db = missing.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db");
            assertTrue(Files.isDirectory(missing));
            assertTrue(Files.isRegularFile(db));

            Path existing = temp.resolve("already-there");
            Files.createDirectories(existing);
            AnalysisStorageValidator.Result reused = service.saveAnalysisStorage(existing);
            assertTrue(reused.valid(), reused.message());
            assertEquals(existing.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db"),
                    service.activeDatabasePath().orElseThrow());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void invalidPathKeepsTheWorkingDatabase(@TempDir Path temp) throws Exception {
        installPackagedHome(temp.resolve("home"));
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            Path good = temp.resolve("good storage");
            assertTrue(service.saveAnalysisStorage(good).valid());
            Path goodDb = service.activeDatabasePath().orElseThrow();
            service.newProject("Kept");

            Path file = Files.createFile(temp.resolve("not-a-folder"));
            AnalysisStorageValidator.Result rejected = service.saveAnalysisStorage(file);
            assertFalse(rejected.valid());
            assertTrue(rejected.message() != null && !rejected.message().isBlank());
            assertEquals(goodDb, service.activeDatabasePath().orElseThrow());
            assertEquals(good.toAbsolutePath().normalize(), service.analysisStorage().userSelectedRoot().orElseThrow());
            assertTrue(names(service).contains("Kept"));

            AnalysisStorageValidator.Result blank = service.saveAnalysisStorage(null);
            assertFalse(blank.valid());
            assertEquals(goodDb, service.activeDatabasePath().orElseThrow());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void packagedLaunchStillUsesConfiguredDatabaseNotApplicationHome(@TempDir Path temp) throws Exception {
        Path packagedHome = temp.resolve("LocalAppData").resolve("StellarAI");
        installPackagedHome(packagedHome);
        assertTrue(StellarAiPaths.isPackagedLaunch());
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            service.savedProjects();
            assertTrue(service.activeDatabasePath().isEmpty());
            assertFalse(Files.exists(packagedHome.resolve("database").resolve("stellar.db")));
            Path selected = temp.resolve("operator choice");
            assertTrue(service.saveAnalysisStorage(selected).valid());
            Path db = service.activeDatabasePath().orElseThrow();
            assertEquals(selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db"), db);
            assertFalse(db.startsWith(packagedHome));
            assertEquals(packagedHome.toAbsolutePath().normalize(), StellarAiPaths.homeRoot());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void liveFolderReceivesCanonicalDatabaseWhenDriveExists(@TempDir Path temp) throws Exception {
        Path drive = Path.of("D:\\");
        if (!Files.isDirectory(drive)) {
            return;
        }
        Path selected = Path.of("D:\\StellarAI-Test");
        installPackagedHome(temp.resolve("live-home"));
        DesktopAppService service = new DesktopAppService(new DesktopSession());
        try {
            AnalysisStorageValidator.Result saved = service.saveAnalysisStorage(selected);
            assertTrue(saved.valid(), saved.message());
            Path db = selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db");
            assertEquals(db, service.activeDatabasePath().orElseThrow());
            assertTrue(Files.isRegularFile(db));
            String liveName = "StoragePathLive" + System.currentTimeMillis();
            service.newProject(liveName);
            assertTrue(names(service).contains(liveName));
        } finally {
            service.shutdown();
        }
        DesktopAppService restarted = new DesktopAppService(new DesktopSession());
        try {
            assertEquals(selected.toAbsolutePath().normalize(), restarted.analysisStorage().userSelectedRoot().orElseThrow());
            assertTrue(restarted.savedProjects().stream().anyMatch(p -> p.name().startsWith("StoragePathLive")));
            assertEquals(selected.resolve("StellarAIAnalysis").resolve("database").resolve("stellar.db"),
                    restarted.activeDatabasePath().orElseThrow());
        } finally {
            restarted.shutdown();
        }
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

    private static List<String> names(DesktopAppService service) {
        return service.savedProjects().stream().map(p -> p.name()).toList();
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
