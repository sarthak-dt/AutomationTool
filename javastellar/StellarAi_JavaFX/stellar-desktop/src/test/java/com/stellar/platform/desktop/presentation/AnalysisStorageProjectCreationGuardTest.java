package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import com.stellar.platform.storage.analysis.AnalysisStorageValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStorageProjectCreationGuardTest {

    @TempDir Path temp;
    private String previousHome;
    private String previousDisableAuto;

    @AfterEach
    void tearDown() {
        AnalysisStorageRoots.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
        if (previousDisableAuto == null) {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        } else {
            System.setProperty("stellarai.analysis.storage.disable-auto", previousDisableAuto);
        }
        System.clearProperty("stellarai.metadata.db");
    }

    @Test
    void createProjectRejectedWhenNotConfigured() {
        isolateWithoutAutoStorage();
        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("empty-config.properties")));
        assertEquals(AnalysisStorageValidator.Status.NOT_CONFIGURED, storage.currentStatus().status());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        assertThrows(AnalysisStorageRequiredException.class, () -> service.newProject("Blocked"));
    }

    @Test
    void createProjectAllowedWhenStorageValid() throws Exception {
        isolateWithoutAutoStorage();
        Path root = temp.resolve("valid-root");
        Files.createDirectories(root);
        AnalysisStorageSettingsStore store = new AnalysisStorageSettingsStore(temp.resolve("cfg.properties"));
        AnalysisStorageService storage = new AnalysisStorageService(store);
        assertTrue(storage.save(root).valid());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        assertDoesNotThrow(() -> service.newProject("Allowed"));
        assertTrue(service.isDurableNamedProject());
        service.shutdown();
    }

    @Test
    void createProjectRejectedWhenPathInvalid() throws Exception {
        isolateWithoutAutoStorage();
        Path file = temp.resolve("not-dir.txt");
        Files.writeString(file, "x");
        AnalysisStorageSettingsStore store = new AnalysisStorageSettingsStore(temp.resolve("cfg2.properties"));
        // Seed a previously valid path then break it.
        Path good = temp.resolve("was-good");
        Files.createDirectories(good);
        AnalysisStorageService storage = new AnalysisStorageService(store);
        assertTrue(storage.save(good).valid());
        Files.delete(good.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME).resolve("projects"));
        // Replace controlled dir with a file to force INVALID on revalidate.
        Path controlled = good.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME);
        // delete tree
        try (var walk = Files.walk(controlled)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
        Files.writeString(controlled, "not-a-directory");
        storage.revalidate();
        assertFalse(Files.isDirectory(controlled));
        String runtimeTemp = System.getProperty(AnalysisStorageRoots.RUNTIME_TEMP_PROPERTY);
        assertTrue(runtimeTemp == null || !runtimeTemp.startsWith(controlled.toString()),
                "runtime temp still points at the invalid root: " + runtimeTemp);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        AnalysisStorageRequiredException rejected = assertThrows(
                AnalysisStorageRequiredException.class, () -> service.newProject("Nope"));
        assertFalse(rejected.getMessage() != null && rejected.getMessage().contains("Cannot create assets root"),
                rejected.getMessage());
        assertTrue(Files.isRegularFile(controlled), "invalid root must stay a file");
        assertFalse(Files.isDirectory(controlled.resolve("temp")));
        assertFalse(Files.isDirectory(controlled.resolve("projects")));
        assertFalse(Files.isDirectory(controlled.resolve("database")));
        assertFalse(service.isDurableNamedProject());
        service.shutdown();
    }

    @Test
    void missingCreatableRootIsCreatedAndProjectWorks() throws Exception {
        isolateWithoutAutoStorage();
        Path root = temp.resolve("will-be-created");
        assertFalse(Files.exists(root));
        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("create.properties")));
        assertTrue(storage.save(root).valid());
        assertTrue(Files.isDirectory(root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
        assertTrue(Files.isDirectory(root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME).resolve("temp")));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        assertDoesNotThrow(() -> service.newProject("Created"));
        assertTrue(service.isDurableNamedProject());
        service.shutdown();
    }

    @Test
    void settingPersistsAcrossServiceRecreation() throws Exception {
        isolateWithoutAutoStorage();
        Path root = temp.resolve("persist-root");
        Files.createDirectories(root);
        Path settings = temp.resolve("persist.properties");
        AnalysisStorageService first = new AnalysisStorageService(new AnalysisStorageSettingsStore(settings));
        assertTrue(first.save(root).valid());
        AnalysisStorageRoots.clear();
        AnalysisStorageService second = new AnalysisStorageService(new AnalysisStorageSettingsStore(settings));
        assertTrue(second.isValid());
        assertEquals(root.toAbsolutePath().normalize(), second.userSelectedRoot().orElseThrow());
    }

    @Test
    void createProjectAllowedWhenPathContainsSpaces() throws Exception {
        isolateWithoutAutoStorage();
        Path root = temp.resolve("Analysis Storage Root");
        Files.createDirectories(root);
        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("spaces.properties")));
        assertTrue(storage.save(root).valid());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        assertDoesNotThrow(() -> service.newProject("Spaced"));
        service.shutdown();
    }

    private void isolateWithoutAutoStorage() {
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }
}
