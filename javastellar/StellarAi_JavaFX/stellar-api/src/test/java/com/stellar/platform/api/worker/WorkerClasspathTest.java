package com.stellar.platform.api.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class WorkerClasspathTest {

    @AfterEach
    void clear() {
        System.clearProperty("stellar.worker.classpath");
        System.clearProperty("jpackage.app-path");
    }

    @Test
    void mavenClasspathIsUsedWhenNotPackaged() {
        String cp = WorkerClasspath.resolve();
        assertFalse(cp.isBlank());
        assertTrue(cp.contains("stellar") || cp.contains(".jar") || cp.contains("classes"));
        assertFalse(WorkerClasspath.packagedLayoutAvailable());
    }

    @Test
    void overridePropertyWins() {
        System.setProperty("stellar.worker.classpath", "C:\\app\\lib\\stellar.jar");
        assertEqualsPath("C:\\app\\lib\\stellar.jar", WorkerClasspath.resolve());
    }

    @Test
    void packagedLayoutWithSpacesDoesNotUseMaven(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("Stellar AI RC Test").resolve("StellarAI");
        Path app = root.resolve("app");
        Files.createDirectories(app);
        Files.write(app.resolve("stellar-api.jar"), new byte[] {1});
        Files.write(app.resolve("stellar-parser-mf4.jar"), new byte[] {1});
        Files.write(app.resolve("stellar-processing-core.jar"), new byte[] {1});
        Path exe = root.resolve("StellarAI.exe");
        Files.write(exe, new byte[] {0});
        System.setProperty("jpackage.app-path", exe.toAbsolutePath().toString());

        String cp = WorkerClasspath.resolve();
        assertTrue(cp.contains("stellar-api.jar"));
        assertTrue(cp.contains("stellar-parser-mf4.jar"));
        String lower = cp.toLowerCase().replace('\\', '/');
        assertFalse(lower.contains("/.m2/"));
        assertFalse(lower.contains("target/classes"));
        assertTrue(WorkerClasspath.packagedLayoutAvailable());
        WorkerClasspath.rejectDevelopmentPaths(cp, "packaged");
    }

    @Test
    void packagedLaunchRejectsMavenRepository() {
        System.setProperty("jpackage.app-path", "C:\\Program Files\\StellarAI\\StellarAI.exe");
        System.setProperty("stellar.worker.classpath",
                "C:\\Users\\tester\\.m2\\repository\\com\\stellar\\stellar-api.jar");
        IllegalStateException ex = assertThrows(IllegalStateException.class, WorkerClasspath::resolve);
        assertTrue(ex.getMessage().toLowerCase().contains(".m2"));
    }

    private static void assertEqualsPath(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
