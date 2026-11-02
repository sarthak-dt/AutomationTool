package com.stellar.platform.storage.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only isolation for Analysis Storage. Production never sets
 * {@code stellarai.test.isolate}, so this class is a no-op at runtime.
 *
 * <p>When the flag is true, tests must not read or write the operator's
 * {@code ~/.stellarai} settings or the configured Analysis Storage catalog.
 */
public final class AnalysisStorageIsolation {

    public static final String PROPERTY = "stellarai.test.isolate";

    private static final String SETTINGS_RELATIVE = "config/analysis-storage.properties";
    private static final String KEY_ROOT = "analysis.storage.root";
    private static final AtomicReference<Path> PROCESS_HOME = new AtomicReference<>();
    /**
     * Captured when this class loads. Surefire sets {@code stellarai.test.isolate}
     * on the JVM command line, so a later test cannot turn the write guard off
     * by clearing that property.
     */
    private static final boolean AUTOMATED_JVM = detectAutomatedJvm();

    private static boolean detectAutomatedJvm() {
        String isolate = System.getProperty(PROPERTY, "").trim();
        if ("true".equalsIgnoreCase(isolate)) {
            return true;
        }
        if (System.getProperty("surefire.test.class.path") != null
                || System.getProperty("surefire.real.class.path") != null) {
            return true;
        }
        try {
            return StackWalker.getInstance().walk(frames -> frames.limit(40)
                    .anyMatch(frame -> frame.getClassName().startsWith("org.junit.")));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** True for a Surefire JVM even after a test sets {@code stellarai.test.isolate=false}. */
    public static boolean automatedJvm() {
        return AUTOMATED_JVM;
    }

    private AnalysisStorageIsolation() {
    }

    public static boolean enabled() {
        String explicit = System.getProperty(PROPERTY, "").trim();
        if ("false".equalsIgnoreCase(explicit)) {
            return false;
        }
        if ("true".equalsIgnoreCase(explicit)) {
            return true;
        }
        return testRuntimeDetected();
    }

    private static boolean testRuntimeDetected() {
        if (System.getProperty("surefire.test.class.path") != null
                || System.getProperty("surefire.real.class.path") != null) {
            return true;
        }
        try {
            return StackWalker.getInstance().walk(frames -> frames.limit(40)
                    .anyMatch(frame -> frame.getClassName().startsWith("org.junit.")));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void enable() {
        System.setProperty(PROPERTY, "true");
    }

    /**
     * Temporarily disables isolation so a test can observe production path
     * resolution. Never use this to open the operator catalog.
     */
    public static void runUnisolated(Runnable action) {
        String previous = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, "false");
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, previous);
            }
        }
    }

    /**
     * Process-wide temp home used when isolation is on and {@code stellarai.home}
     * was not set. Never {@code ~/.stellarai}.
     */
    public static Path processIsolatedHome() {
        Path existing = PROCESS_HOME.get();
        if (existing != null) {
            return existing;
        }
        try {
            Path created = Files.createTempDirectory("stellarai-isolated-home-").toAbsolutePath().normalize();
            if (PROCESS_HOME.compareAndSet(null, created)) {
                created.toFile().deleteOnExit();
                return created;
            }
            return PROCESS_HOME.get();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create isolated test home", ex);
        }
    }

    /** Settings files that belong to the interactive operator, never to tests. */
    public static List<Path> operatorSettingsFiles() {
        List<Path> files = new ArrayList<>();
        String userHome = System.getProperty("user.home", "").trim();
        if (!userHome.isEmpty()) {
            files.add(Path.of(userHome, ".stellarai", "config", "analysis-storage.properties")
                    .toAbsolutePath().normalize());
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            files.add(Path.of(localAppData, "StellarAI", "config", "analysis-storage.properties")
                    .toAbsolutePath().normalize());
        }
        return List.copyOf(files);
    }

    public static Optional<Path> operatorConfiguredRoot() {
        for (Path settings : operatorSettingsFiles()) {
            Optional<Path> root = readRoot(settings);
            if (root.isPresent()) {
                return root;
            }
        }
        return Optional.empty();
    }

    public static Optional<Path> operatorHome() {
        String userHome = System.getProperty("user.home", "").trim();
        if (userHome.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(userHome, ".stellarai").toAbsolutePath().normalize());
    }

    public static void refuseOperatorWorkspace(Path candidate) {
        if (candidate == null || (!enabled() && !AUTOMATED_JVM)) {
            return;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path campaign : campaignHomes()) {
            if (isSameOrChild(campaign, normalized)) {
                throw refused(normalized);
            }
        }
        for (Path settings : operatorSettingsFiles()) {
            if (normalized.equals(settings) || isSameOrChild(settings.getParent(), normalized)) {
                throw refused(normalized);
            }
        }
        operatorHome().ifPresent(home -> {
            if (isSameOrChild(home, normalized)) {
                throw refused(normalized);
            }
        });
        operatorConfiguredRoot().ifPresent(root -> {
            Path controlled = root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME).toAbsolutePath().normalize();
            if (isSameOrChild(root, normalized) || isSameOrChild(controlled, normalized)) {
                throw refused(normalized);
            }
        });
    }

    public static boolean isOperatorWorkspace(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path settings : operatorSettingsFiles()) {
            if (normalized.equals(settings) || isSameOrChild(settings.getParent(), normalized)) {
                return true;
            }
        }
        if (operatorHome().filter(home -> isSameOrChild(home, normalized)).isPresent()) {
            return true;
        }
        return operatorConfiguredRoot()
                .map(root -> {
                    Path controlled = root.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)
                            .toAbsolutePath().normalize();
                    return isSameOrChild(root, normalized) || isSameOrChild(controlled, normalized);
                })
                .orElse(false);
    }

    /**
     * Campaign and operator directories that an automated test must not open.
     * Production launches do not set the isolation property, so these paths
     * stay available to an installed application.
     */
    static List<Path> campaignHomes() {
        return List.of(
                Path.of("D:/StellarAI-E2E").toAbsolutePath().normalize(),
                Path.of("D:/config").toAbsolutePath().normalize());
    }

    private static Optional<Path> readRoot(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(settingsFile)) {
            props.load(in);
        } catch (IOException ignored) {
            return Optional.empty();
        }
        String value = props.getProperty(KEY_ROOT, "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value).toAbsolutePath().normalize());
    }

    private static boolean isSameOrChild(Path parent, Path candidate) {
        if (parent == null || candidate == null) {
            return false;
        }
        Path left = parent.toAbsolutePath().normalize();
        Path right = candidate.toAbsolutePath().normalize();
        return right.equals(left) || right.startsWith(left);
    }

    private static IllegalStateException refused(Path path) {
        return new IllegalStateException(
                "Test isolation refused access to the operator Analysis Storage or ~/.stellarai: "
                        + path);
    }

    static String settingsRelativePath() {
        return SETTINGS_RELATIVE;
    }
}
