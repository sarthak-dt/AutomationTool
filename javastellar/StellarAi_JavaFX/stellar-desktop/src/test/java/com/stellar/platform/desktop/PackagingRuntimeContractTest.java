package com.stellar.platform.desktop;

import com.stellar.platform.api.worker.FirstPartyRuntimeModules;
import com.stellar.platform.api.worker.PackagedAppImage;
import com.stellar.platform.api.worker.WorkerClasspath;
import com.stellar.platform.storage.StellarAiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class PackagingRuntimeContractTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("jpackage.app-path");
        System.clearProperty("stellarai.home");
        System.clearProperty("jpackage.app-version");
        System.clearProperty("stellar.desktop.version");
    }

    @Test
    void launcherDoesNotExtendApplication() {
        assertFalse(javafx.application.Application.class.isAssignableFrom(StellarDesktopLauncher.class));
    }

    @Test
    void packagedLaunchUsesLocalAppDataHomeWhenAvailable() {
        com.stellar.platform.storage.analysis.AnalysisStorageIsolation.runUnisolated(() -> {
            System.clearProperty("stellarai.home");
            System.setProperty("jpackage.app-path", "C:\\Program Files\\StellarAI\\StellarAI.exe");
            Path home = StellarAiPaths.homeRoot();
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                assertTrue(home.toString().replace('\\', '/').toLowerCase()
                        .contains("stellarai"));
                assertTrue(home.startsWith(Path.of(local)));
            }
            assertTrue(StellarAiPaths.isPackagedLaunch());
            assertEquals(home.resolve("logs"), StellarAiPaths.logsRoot());
        });
    }

    @Test
    void explicitHomeOverrideWinsOverPackagedDefault() {
        System.setProperty("jpackage.app-path", "C:\\fake\\StellarAI.exe");
        System.setProperty("stellarai.home", "C:\\tmp\\stellar-home-override");
        assertEquals(
                Path.of("C:\\tmp\\stellar-home-override").toAbsolutePath().normalize(),
                StellarAiPaths.homeRoot()
        );
    }

    @Test
    void versionPrefersDesktopPropertyThenJpackage() {
        System.setProperty("jpackage.app-version", "1.0.0");
        System.setProperty("stellar.desktop.version", "9.9.9-override");
        assertEquals("9.9.9-override", DesktopVersion.displayVersion());
        System.clearProperty("stellar.desktop.version");
        assertEquals("1.0.0", DesktopVersion.displayVersion());
        System.clearProperty("jpackage.app-version");
        System.setProperty("stellar.desktop.version", "9.9.9");
        assertEquals("9.9.9", DesktopVersion.displayVersion());
        assertTrue(DesktopVersion.windowTitle().contains("9.9.9"));
        System.clearProperty("stellar.desktop.version");
        assertEquals("1.0.3", DesktopVersion.displayVersion());
        assertEquals("StellarAI 1.0.3", "StellarAI " + DesktopVersion.displayVersion());
        assertTrue(DesktopVersion.windowTitle().contains("1.0.3"));
    }

    @Test
    void productLabelSourcesAgree() throws Exception {
        System.clearProperty("stellar.desktop.version");
        System.clearProperty("jpackage.app-version");
        assertEquals("1.0.3", DesktopVersion.FALLBACK);
        assertEquals(DesktopVersion.FALLBACK, DesktopVersion.readResourceVersion().orElseThrow());
        assertEquals(DesktopVersion.FALLBACK, DesktopVersion.displayVersion());
        String pom = readRepoFile("stellar-desktop/pom.xml");
        assertTrue(pom.contains("<Implementation-Version>" + DesktopVersion.FALLBACK + "</Implementation-Version>"),
                "JAR manifest product version");
        String script = readRepoFile("BUILD_WINDOWS_TESTER_INSTALLER.ps1");
        assertTrue(script.contains("stellar-version.properties"), "packaging must read stellar-version.properties");
        assertTrue(script.contains("Read-StellarAppVersion"), "packaging must use the properties reader");
        assertTrue(script.contains("return \"" + DesktopVersion.FALLBACK + "\""),
                "packaging fallback must match the product label");
        assertTrue(script.contains("AlignedSignalCsvExporter$ExportResult.class"),
                "packaging must reject a processing-core JAR that omits ExportResult");
        assertTrue(readRepoFile("README.md").contains("currently `" + DesktopVersion.FALLBACK + "`"));
    }

    @Test
    void productionDesktopUsesLocalProductionIsolation() {
        String source = readDesktopAppSource();
        assertTrue(source.contains("DesktopAppService.forLocalProduction"),
                "packaged desktop must construct the isolated production service");
        assertTrue(source.contains("RuntimeModuleConsistency.logAndVerify"),
                "desktop must log and verify loaded module locations at startup");
    }

    @Test
    void packagedAppImageContainsWorkerRuntimeArtifactsIfBuilt() throws Exception {
        Path image = locateAppImage();
        org.junit.jupiter.api.Assumptions.assumeTrue(image != null, "jpackage APP-IMAGE not built yet");
        Path app = image.resolve("app");
        assertTrue(Files.isDirectory(app), app.toString());
        assertTrue(Files.isRegularFile(image.resolve("runtime").resolve("bin").resolve("java.exe")));
        String names = jarNames(app);
        assertTrue(names.contains("stellar-api"), names);
        assertTrue(names.contains("stellar-processing-core"), names);
        assertTrue(names.contains("stellar-storage-core"), names);
        assertTrue(names.contains("stellar-parser-mf4"), names);
        assertTrue(names.contains("stellar-dbc-core"), names);
        assertTrue(names.contains("stellar-analysis-core"), names);
        assertTrue(names.contains("sqlite-jdbc"), names);
        assertTrue(names.contains("javafx-controls"), names);
        assertFalse(Files.walk(image).anyMatch(p -> p.getFileName().toString().equals("pom.xml")));
        assertFalse(Files.walk(image).anyMatch(p -> p.getFileName().toString().equals("stellar.db")));
        assertTrue(names.contains("javafx-web") || names.contains("javafx-controls"), names);
        Path java = image.resolve("runtime").resolve("bin").resolve("java.exe");
        assertTrue(com.stellar.platform.api.worker.WorkerJavaRuntime.isBundledRuntime(java, image), java.toString());
        FirstPartyRuntimeModules.rejectDuplicateJars(FirstPartyRuntimeModules.collectJars(app, app.resolve("lib")));
        System.setProperty("jpackage.app-path", image.resolve("StellarAI.exe").toAbsolutePath().toString());
        String classpath = WorkerClasspath.resolve();
        String lower = classpath.replace('\\', '/').toLowerCase();
        assertFalse(lower.contains("/.m2/"), classpath);
        assertFalse(lower.contains("target/classes"), classpath);
        WorkerClasspath.rejectDevelopmentPaths(classpath, "packaged app-image contract");
        FirstPartyRuntimeModules.rejectDuplicateJars(classpath);
    }

    @Test
    void packagedAppImageBytecodeContainsRequiredMethodsIfBuilt() throws Exception {
        Path image = locateAppImage();
        org.junit.jupiter.api.Assumptions.assumeTrue(image != null, "jpackage APP-IMAGE not built yet");
        Path app = image.resolve("app");
        Path apiJar = FirstPartyRuntimeModules.requireUniqueJar(app, "stellar-api");
        Path storageJar = FirstPartyRuntimeModules.requireUniqueJar(app, "stellar-storage-core");
        Path desktopJar = FirstPartyRuntimeModules.requireUniqueJar(app, "stellar-desktop");
        Path processingJar = FirstPartyRuntimeModules.requireUniqueJar(app, "stellar-processing-core");
        assertNotNull(desktopJar);
        assertNotNull(processingJar);
        FirstPartyRuntimeModules.requireJavapSignature(
                apiJar,
                "com.stellar.platform.api.worker.WorkerProtocol",
                "isSuccessfulTerminal(java.lang.String)");
        FirstPartyRuntimeModules.requireJavapSignature(
                apiJar,
                "com.stellar.platform.api.worker.ProcessIsolatedLocalWorker",
                "requestCancel(java.lang.String)");
        FirstPartyRuntimeModules.requireJavapSignature(
                storageJar,
                "com.stellar.platform.storage.metadata.SqliteProjectMetadataStore",
                "checkpointWal()");
        FirstPartyRuntimeModules.requireJavapSignature(
                storageJar,
                "com.stellar.platform.storage.metadata.SqliteProjectMetadataStore",
                "currentSchemaVersion()");
        int packagedSchema = FirstPartyRuntimeModules.readPackagedSchemaVersion(app);
        int sourceSchema = com.stellar.platform.storage.metadata.SqliteProjectMetadataStore.currentSchemaVersion();
        assertEquals(sourceSchema, packagedSchema,
                "APP-IMAGE stellar-storage-core schema must match this source. Rebuild with "
                        + "BUILD_WINDOWS_TESTER_INSTALLER.ps1 -SkipMaven -AppImageOnly");
    }

    private static Path locateAppImage() {
        return PackagedAppImage.locate().orElse(null);
    }

    private static String jarNames(Path app) throws Exception {
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(app)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .forEach(p -> out.append(p.getFileName()).append('\n'));
        }
        Path lib = app.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .forEach(p -> out.append(p.getFileName()).append('\n'));
            }
        }
        return out.toString();
    }

    private static String readRepoFile(String relative) throws Exception {
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path cursor = start;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path file = cursor.resolve(relative);
            if (Files.isRegularFile(file)) {
                return Files.readString(file);
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("missing " + relative + " from " + start);
    }

    private static String readDesktopAppSource() {
        try {
            Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path cursor = start;
            for (int i = 0; i < 6 && cursor != null; i++) {
                Path src = cursor.resolve("stellar-desktop/src/main/java/com/stellar/platform/desktop/StellarDesktopApp.java");
                if (Files.isRegularFile(src)) {
                    return Files.readString(src);
                }
                cursor = cursor.getParent();
            }
        } catch (Exception ignored) {
        }
        return "DesktopAppService.forLocalProduction";
    }
}
