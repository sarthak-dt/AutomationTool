package com.stellar.platform.processing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hook for optional real-file compatibility tests outside Git.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>system property {@code stellar.externalTestData}</li>
 *   <li>environment variable {@code STELLAR_EXTERNAL_TEST_DATA}</li>
 *   <li>default directory {@code external-test-data} relative to user.dir</li>
 * </ol>
 * Tests should skip cleanly when the directory (or required files) are absent.
 */
public final class ExternalTestData {

    public static final String SYSTEM_PROPERTY = "stellar.externalTestData";
    public static final String ENV_VARIABLE = "STELLAR_EXTERNAL_TEST_DATA";
    public static final String DEFAULT_DIR = "external-test-data";

    private ExternalTestData() {
    }

    public static Path root() {
        String prop = System.getProperty(SYSTEM_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        String env = System.getenv(ENV_VARIABLE);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return Path.of(System.getProperty("user.dir", ".")).resolve(DEFAULT_DIR);
    }

    public static boolean isAvailable() {
        Path root = root();
        return Files.isDirectory(root);
    }

    public static Path resolve(String relative) {
        return root().resolve(relative);
    }

    public static boolean hasFile(String relative) {
        return Files.isRegularFile(resolve(relative));
    }
}
