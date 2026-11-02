package com.stellar.platform.desktop.support;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class DiagnosticsSupportBundleTest {

    @TempDir
    Path temp;

    @Test
    void bundleExcludesRawDataAndPrivatePaths() throws Exception {
        Path log = temp.resolve("stellar-desktop.log");
        Files.writeString(log, "ok " + System.getProperty("user.home") + " token-must-not-appear\n");
        Path zip = temp.resolve("support.zip");
        DiagnosticsSupportBundle.write(zip, new DiagnosticsSupportBundle.Snapshot(
                DesktopVersion.displayVersion(),
                SqliteProjectMetadataStore.currentSchemaVersion(),
                "21",
                "Windows",
                "amd64",
                System.getProperty("user.home") + "\\StellarAIAnalysis",
                "pid=12 alive=true isolation=PROCESS_ISOLATED",
                List.of("WARNING: gap on channel 1"),
                List.of("PROJECT_SAVED"),
                log
        ), null);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertTrue(zf.getEntry("manifest.json") != null);
            assertTrue(zf.getEntry("configuration.txt") != null);
            assertTrue(zf.getEntry("worker.txt") != null);
            assertTrue(zf.getEntry("logs/stellar-desktop.log") != null);
            var names = zf.stream().map(e -> e.getName().toLowerCase()).toList();
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".mf4")));
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".dbc")));
            assertFalse(names.stream().anyMatch(n -> n.contains("stellar.db")));
            String manifest = new String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"containsRawMf4\":false"));
            assertTrue(manifest.contains("\"containsIpcToken\":false"));
            assertTrue(manifest.contains("1.0.0-rc1") || manifest.contains(DesktopVersion.displayVersion()));
            String config = new String(zf.getInputStream(zf.getEntry("configuration.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertFalse(config.contains(System.getProperty("user.home")));
        }
    }
}
