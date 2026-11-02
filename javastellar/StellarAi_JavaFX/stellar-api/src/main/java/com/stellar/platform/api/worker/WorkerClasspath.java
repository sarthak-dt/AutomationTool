package com.stellar.platform.api.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Resolves the child-worker classpath. Packaged Windows installs must not depend on Maven.
 */
public final class WorkerClasspath {

    public static final String OVERRIDE_PROPERTY = "stellar.worker.classpath";

    private WorkerClasspath() {
    }

    public static String resolve() {
        String override = System.getProperty(OVERRIDE_PROPERTY, "").trim();
        if (!override.isBlank()) {
            if (isPackagedLaunch()) {
                rejectDevelopmentPaths(override, "stellar.worker.classpath override");
            }
            FirstPartyRuntimeModules.rejectDuplicateJars(override);
            return override;
        }
        if (isPackagedLaunch()) {
            String packagedCp = packagedClasspathOrThrow();
            rejectDevelopmentPaths(packagedCp, "packaged application layout");
            FirstPartyRuntimeModules.rejectDuplicateJars(packagedCp);
            return packagedCp;
        }
        // Surefire often puts the real test classpath here; java.class.path may be a booter jar.
        String surefire = System.getProperty("surefire.test.class.path", "").trim();
        if (!surefire.isBlank() && looksComplete(surefire)) {
            FirstPartyRuntimeModules.rejectDuplicateJars(surefire);
            return surefire;
        }
        String cp = System.getProperty("java.class.path", "");
        if (cp == null || cp.isBlank()) {
            throw new IllegalStateException(
                    "Local worker classpath is empty. Packaged installs must include worker JARs next to the application.");
        }
        FirstPartyRuntimeModules.rejectDuplicateJars(cp);
        return cp;
    }

    public static boolean packagedLayoutAvailable() {
        if (!isPackagedLaunch()) {
            return false;
        }
        try {
            return !packagedClasspathOrThrow().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static boolean isPackagedLaunch() {
        return !System.getProperty("jpackage.app-path", "").trim().isBlank();
    }

    public static Path installRoot() {
        String appPath = System.getProperty("jpackage.app-path", "").trim();
        if (appPath.isBlank()) {
            throw new IllegalStateException("jpackage.app-path is not set");
        }
        Path exe = Path.of(appPath).toAbsolutePath().normalize();
        Path parent = exe.getParent();
        if (parent == null) {
            throw new IllegalStateException("Cannot resolve install root from " + exe);
        }
        return parent;
    }

    public static void rejectDevelopmentPaths(String classpath, String source) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        String normalized = classpath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/.m2/") || normalized.contains("/.m2")) {
            throw new IllegalStateException(source + " must not use the Maven local repository (.m2): " + classpath);
        }
        if (normalized.contains("target/classes") || normalized.contains("target/test-classes")) {
            throw new IllegalStateException(source + " must not use Maven target/classes: " + classpath);
        }
        if (normalized.contains("/src/main/") || normalized.contains("/src/test/")) {
            throw new IllegalStateException(source + " must not use the source tree: " + classpath);
        }
    }

    static String packagedClasspathOrThrow() {
        Path installRoot = installRoot();
        List<Path> jars = new ArrayList<>();
        collectJars(installRoot.resolve("app"), jars);
        collectJars(installRoot.resolve("app").resolve("lib"), jars);
        if (jars.isEmpty()) {
            throw new IllegalStateException(
                    "Packaged application has no JARs under app/ or app/lib. Worker cannot start. Root=" + installRoot);
        }
        String sep = System.getProperty("path.separator");
        StringBuilder out = new StringBuilder();
        for (Path jar : jars) {
            if (out.length() > 0) {
                out.append(sep);
            }
            out.append(jar.toAbsolutePath().normalize());
        }
        return out.toString();
    }

    private static boolean looksComplete(String classpath) {
        String lower = classpath.toLowerCase(Locale.ROOT);
        return lower.contains("stellar-api") || lower.contains("stellar-processing")
                || lower.contains("parser") || classpath.contains(System.getProperty("path.separator", ";"));
    }

    private static void collectJars(Path dir, List<Path> jars) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName() != null && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot list packaged JARs in " + dir, ex);
        }
    }
}
