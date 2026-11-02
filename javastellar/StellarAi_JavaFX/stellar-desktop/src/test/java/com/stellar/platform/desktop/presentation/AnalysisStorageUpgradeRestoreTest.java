package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageUpgradeRestoreTest {

    @AfterEach
    void clearRoot() {
        AnalysisStorageRoots.clear();
        System.clearProperty("stellarai.analysis.storage.disable-auto");
    }

    @Test
    void startupRestoresLegacyRootWithoutCreatingAPackagedDatabase(@TempDir Path temp) throws Exception {
        Path selected = temp.resolve("existing-storage");
        Path controlled = selected.resolve("StellarAIAnalysis");
        Path marker = controlled.resolve("projects").resolve("1023").resolve("signal-store").resolve("keep.bin");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "processed");
        Path legacyHome = temp.resolve("profile").resolve(".stellarai");
        Path legacy = legacyHome.resolve("config").resolve("analysis-storage.properties");
        Files.createDirectories(legacy.getParent());
        Properties properties = new Properties();
        properties.setProperty("analysis.storage.root", selected.toAbsolutePath().normalize().toString());
        try (var out = Files.newOutputStream(legacy)) {
            properties.store(out, "isolated copy");
        }
        byte[] before = Files.readAllBytes(legacy);
        Path packagedSettings = temp.resolve("Local").resolve("StellarAI")
                .resolve("config").resolve("analysis-storage.properties");

        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packagedSettings, legacy, false);
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        try {
            AnalysisStorageService service = new AnalysisStorageService(store);
            assertTrue(service.isValid());
            assertEquals(selected.toAbsolutePath().normalize(), service.userSelectedRoot().orElseThrow());
        } finally {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        }

        assertArrayEqualsUnchanged(before, legacy);
        assertEquals("processed", Files.readString(marker));
        assertFalse(Files.exists(packagedSettings));
        assertFalse(Files.exists(temp.resolve("Local").resolve("StellarAI").resolve("database")));
    }

    @Test
    void freshConfigurationRemainsUnset(@TempDir Path temp) throws Exception {
        Path legacy = temp.resolve("profile").resolve(".stellarai")
                .resolve("config").resolve("analysis-storage.properties");
        Path packaged = temp.resolve("Local").resolve("StellarAI")
                .resolve("config").resolve("analysis-storage.properties");
        AnalysisStorageSettingsStore store = AnalysisStorageSettingsStore.resolve(packaged, legacy, false);
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        try {
            AnalysisStorageService service = new AnalysisStorageService(store);
            assertFalse(service.isValid());
            assertTrue(service.userSelectedRoot().isEmpty());
        } finally {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        }
        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(packaged));
    }

    private static void assertArrayEqualsUnchanged(byte[] before, Path file) throws Exception {
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(file));
    }
}
