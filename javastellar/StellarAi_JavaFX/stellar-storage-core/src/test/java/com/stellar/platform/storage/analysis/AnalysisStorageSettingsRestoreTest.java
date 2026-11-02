package com.stellar.platform.storage.analysis;

import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnalysisStorageSettingsRestoreTest {

    @TempDir
    Path temp;

    @Test
    void legacySelectionSurvivesPackagedHomeAndIsNotRewritten() throws Exception {
        Path selected = temp.resolve("storage-root");
        Path legacy = writeSelection(temp.resolve("profile").resolve(".stellarai"), selected);
        Path packaged = temp.resolve("Local").resolve("StellarAI")
                .resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);
        byte[] before = Files.readAllBytes(legacy);
        FileTime modified = Files.getLastModifiedTime(legacy);

        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);

        assertEquals(legacy.toAbsolutePath().normalize(), store.settingsFile());
        assertEquals(selected.toAbsolutePath().normalize(), store.loadUserSelectedRoot().orElseThrow());
        assertFalse(Files.exists(packaged));
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals(modified, Files.getLastModifiedTime(legacy));
    }

    @Test
    void packagedOnlySelectionIsCopiedToTheLegacyPointer() throws Exception {
        Path selected = temp.resolve("packaged-root");
        Files.createDirectories(selected.resolve("StellarAIAnalysis").resolve("projects"));
        Path marker = selected.resolve("StellarAIAnalysis").resolve("projects").resolve("keep.txt");
        Files.writeString(marker, "artifact");
        Path packagedHome = temp.resolve("Local").resolve("StellarAI");
        Path packaged = writeSelection(packagedHome, selected);
        Path legacy = temp.resolve("profile").resolve(".stellarai")
                .resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);
        byte[] packagedBefore = Files.readAllBytes(packaged);

        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);
        assertEquals(selected.toAbsolutePath().normalize(), store.loadUserSelectedRoot().orElseThrow());

        assertArrayEquals(packagedBefore, Files.readAllBytes(packaged));
        assertEquals("artifact", Files.readString(marker));
        assertTrue(Files.isRegularFile(legacy));
        Properties restored = new Properties();
        try (var in = Files.newInputStream(legacy)) {
            restored.load(in);
        }
        assertEquals(selected.toAbsolutePath().normalize().toString(), restored.getProperty("analysis.storage.root"));
        assertFalse(Files.exists(packagedHome.resolve("database").resolve("stellar.db")));
        assertFalse(Files.exists(packagedHome.resolve("projects")));
    }

    @Test
    void freshInstallStaysUnconfiguredUntilSave() throws Exception {
        Path packaged = temp.resolve("Local").resolve("StellarAI")
                .resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);
        Path legacy = temp.resolve("profile").resolve(".stellarai")
                .resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);
        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);

        assertTrue(store.loadUserSelectedRoot().isEmpty());
        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(packaged));

        Path selected = temp.resolve("chosen-later");
        Files.createDirectories(selected);
        store.saveUserSelectedRoot(selected);

        assertTrue(Files.isRegularFile(legacy));
        assertFalse(Files.exists(packaged));
        AnalysisStorageSettingsStore restarted = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);
        assertEquals(selected.toAbsolutePath().normalize(), restarted.loadUserSelectedRoot().orElseThrow());
    }

    @Test
    void explicitHomeDoesNotReadTheLegacyProfile() throws Exception {
        Path decoyRoot = temp.resolve("decoy-root");
        Path legacy = writeSelection(temp.resolve("profile").resolve(".stellarai"), decoyRoot);
        byte[] before = Files.readAllBytes(legacy);
        Path isolated = temp.resolve("isolated-home")
                .resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);

        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(isolated, legacy, true);

        assertEquals(isolated.toAbsolutePath().normalize(), store.settingsFile());
        assertTrue(store.loadUserSelectedRoot().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(legacy));
    }

    @Test
    void legacySelectionWinsWhenBothFilesExist() throws Exception {
        Path legacyRoot = temp.resolve("legacy-root");
        Path packagedRoot = temp.resolve("packaged-root");
        Path legacy = writeSelection(temp.resolve("profile").resolve(".stellarai"), legacyRoot);
        Path packaged = writeSelection(temp.resolve("Local").resolve("StellarAI"), packagedRoot);
        byte[] legacyBefore = Files.readAllBytes(legacy);
        byte[] packagedBefore = Files.readAllBytes(packaged);

        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);

        assertEquals(legacyRoot.toAbsolutePath().normalize(), store.loadUserSelectedRoot().orElseThrow());
        assertArrayEquals(legacyBefore, Files.readAllBytes(legacy));
        assertArrayEquals(packagedBefore, Files.readAllBytes(packaged));
    }

    @Test
    void operatorSettingsCopyIsNotModified() throws Exception {
        Path original = AnalysisStorageSettingsStore.legacySettingsFile();
        assumeTrue(Files.isRegularFile(original));
        byte[] before = Files.readAllBytes(original);
        FileTime modified = Files.getLastModifiedTime(original);
        Path copy = temp.resolve(AnalysisStorageSettingsStore.FILE_NAME);
        Files.write(copy, before);
        Properties properties = new Properties();
        try (var in = Files.newInputStream(copy)) {
            properties.load(in);
        }
        assertFalse(properties.getProperty("analysis.storage.root", "").isBlank());
        Path replacement = temp.resolve("replacement-root");
        properties.setProperty("analysis.storage.root", replacement.toString());
        try (OutputStream out = Files.newOutputStream(copy)) {
            properties.store(out, "isolated copy");
        }
        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(
                temp.resolve("unused-packaged.properties"), copy, false);
        assertEquals(replacement.toAbsolutePath().normalize(), store.loadUserSelectedRoot().orElseThrow());
        assertArrayEquals(before, Files.readAllBytes(original));
        assertEquals(modified, Files.getLastModifiedTime(original));
    }

    @Test
    void copiedOperatorDatabaseOpensWithoutChangingTheOriginal() throws Exception {
        Path original = AnalysisStorageIsolation.operatorConfiguredRoot()
                .map(root -> root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)
                        .resolve("database")
                        .resolve("stellar.db"))
                .orElse(null);
        assumeTrue(original != null && Files.isRegularFile(original));
        byte[] before = Files.readAllBytes(original);
        FileTime modified = Files.getLastModifiedTime(original);
        Path copy = temp.resolve("stellar.db");
        Files.write(copy, before);
        int projectsBefore = count(copy, "SELECT COUNT(*) FROM projects");
        int signalsBefore = count(copy, "SELECT COUNT(*) FROM signal_catalog");
        int mappingsBefore = count(copy, "SELECT COUNT(*) FROM channel_mappings");
        int graphBefore = count(copy, "SELECT COUNT(*) FROM graph_investigation");
        int mapBefore = count(copy, "SELECT COUNT(*) FROM map_route_state");

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(copy)) {
            assertEquals(projectsBefore, store.listProjects().size());
        }
        assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), schemaVersion(copy));
        assertEquals(signalsBefore, count(copy, "SELECT COUNT(*) FROM signal_catalog"));
        assertEquals(mappingsBefore, count(copy, "SELECT COUNT(*) FROM channel_mappings"));
        assertEquals(graphBefore, count(copy, "SELECT COUNT(*) FROM graph_investigation"));
        assertEquals(mapBefore, count(copy, "SELECT COUNT(*) FROM map_route_state"));
        assertArrayEquals(before, Files.readAllBytes(original));
        assertEquals(modified, Files.getLastModifiedTime(original));
    }

    private static Path writeSelection(Path home, Path selected) throws Exception {
        Path settings = home.resolve("config").resolve(AnalysisStorageSettingsStore.FILE_NAME);
        Files.createDirectories(settings.getParent());
        Properties properties = new Properties();
        properties.setProperty("analysis.storage.root", selected.toAbsolutePath().normalize().toString());
        try (OutputStream out = Files.newOutputStream(settings)) {
            properties.store(out, "isolated copy");
        }
        return settings;
    }

    private static int schemaVersion(Path database) throws Exception {
        return count(database, "SELECT version FROM schema_info");
    }

    private static int count(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            assertTrue(results.next());
            return results.getInt(1);
        }
    }
}
