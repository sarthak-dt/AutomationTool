package com.stellar.platform.api.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstPartyRuntimeModulesTest {

    @Test
    void artifactIdUsesLongestModuleName() {
        assertEquals("stellar-parser-mf4",
                FirstPartyRuntimeModules.artifactIdFromJarName("stellar-parser-mf4-0.1.0-SNAPSHOT.jar"));
        assertEquals("stellar-api",
                FirstPartyRuntimeModules.artifactIdFromJarName("stellar-api-0.1.0-SNAPSHOT.jar"));
        assertEquals("stellar-storage-core",
                FirstPartyRuntimeModules.artifactIdFromJarName("stellar-storage-core-0.1.0-SNAPSHOT.jar"));
    }

    @Test
    void duplicateFirstPartyJarsAreRejected(@TempDir Path temp) throws Exception {
        Path a = temp.resolve("a");
        Path b = temp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.write(a.resolve("stellar-api-0.1.0-SNAPSHOT.jar"), new byte[] {1});
        Files.write(b.resolve("stellar-api-0.1.0-rc1.jar"), new byte[] {2});
        String classpath = a.resolve("stellar-api-0.1.0-SNAPSHOT.jar")
                + System.getProperty("path.separator")
                + b.resolve("stellar-api-0.1.0-rc1.jar");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FirstPartyRuntimeModules.rejectDuplicateJars(classpath));
        assertTrue(ex.getMessage().contains("stellar-api"));
    }

    @Test
    void uniqueFirstPartyJarIsAccepted(@TempDir Path temp) throws Exception {
        Path app = temp.resolve("app");
        Files.createDirectories(app);
        Files.write(app.resolve("stellar-api-0.1.0-SNAPSHOT.jar"), new byte[] {1});
        Files.write(app.resolve("stellar-storage-core-0.1.0-SNAPSHOT.jar"), new byte[] {1});
        FirstPartyRuntimeModules.rejectDuplicateJars(FirstPartyRuntimeModules.collectJars(app));
        assertEquals("stellar-api", FirstPartyRuntimeModules.artifactIdFromJarName(
                FirstPartyRuntimeModules.requireUniqueJar(app, "stellar-api").getFileName().toString()));
    }

    @Test
    void readPackagedSchemaVersionReadsCurrentStoreBytecode() {
        Path classes = Path.of("").toAbsolutePath().normalize();
        Path cursor = classes;
        Path jar = null;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("stellar-storage-core")
                    .resolve("target")
                    .resolve("stellar-storage-core-0.1.0-SNAPSHOT.jar");
            if (java.nio.file.Files.isRegularFile(candidate)) {
                jar = candidate;
                break;
            }
            cursor = cursor.getParent();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null, "storage-core JAR not built yet");
        Path app = jar.getParent();
        assertEquals(
                com.stellar.platform.storage.metadata.SqliteProjectMetadataStore.currentSchemaVersion(),
                FirstPartyRuntimeModules.readPackagedSchemaVersion(app));
    }

    @Test
    void packagedWorkerClasspathRejectsDuplicateApiJars(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("StellarAI");
        Path app = root.resolve("app");
        Files.createDirectories(app);
        Files.write(app.resolve("stellar-api-0.1.0-SNAPSHOT.jar"), new byte[] {1});
        Files.write(app.resolve("stellar-api-0.1.0-rc1.jar"), new byte[] {2});
        Files.write(root.resolve("StellarAI.exe"), new byte[] {0});
        System.setProperty("jpackage.app-path", root.resolve("StellarAI.exe").toAbsolutePath().toString());
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, WorkerClasspath::resolve);
            assertTrue(ex.getMessage().contains("stellar-api"));
        } finally {
            System.clearProperty("jpackage.app-path");
        }
    }
}
