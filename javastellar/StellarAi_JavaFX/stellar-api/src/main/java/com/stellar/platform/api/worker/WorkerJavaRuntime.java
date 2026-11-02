package com.stellar.platform.api.worker;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the worker JVM executable from the running Java home.
 * Packaged Windows installs must use {@code runtime/bin/java.exe}, never PATH {@code java}.
 */
public final class WorkerJavaRuntime {

    public static final String PROPERTY = "stellar.worker.java";

    private WorkerJavaRuntime() {
    }

    public static Path executable() {
        String override = System.getProperty(PROPERTY, "").trim();
        if (!override.isBlank()) {
            Path configured = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                throw new IllegalStateException("stellar.worker.java does not exist: " + configured);
            }
            return configured;
        }
        String home = System.getProperty("java.home", "");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("java.home is not set; cannot locate the worker Java executable");
        }
        Path bin = Path.of(home).toAbsolutePath().normalize().resolve("bin");
        Path windows = bin.resolve("java.exe");
        if (Files.isRegularFile(windows)) {
            return windows;
        }
        Path unix = bin.resolve("java");
        if (Files.isRegularFile(unix)) {
            return unix;
        }
        throw new IllegalStateException(
                "Worker Java executable was not found under " + bin
                        + ". Packaged installs must use the bundled runtime, not a PATH java.");
    }

    public static boolean isBundledRuntime(Path java, Path installRoot) {
        if (java == null || installRoot == null) {
            return false;
        }
        Path expected = installRoot.toAbsolutePath().normalize().resolve("runtime").resolve("bin");
        Path parent = java.toAbsolutePath().normalize().getParent();
        return parent != null && parent.equals(expected);
    }
}
