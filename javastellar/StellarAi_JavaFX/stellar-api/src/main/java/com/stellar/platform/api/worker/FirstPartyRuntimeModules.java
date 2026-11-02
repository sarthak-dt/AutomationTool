package com.stellar.platform.api.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * First-party StellarAI module identity and classpath uniqueness.
 *
 * <p>A runtime (desktop or worker) must load exactly one copy of each module.
 * Duplicate {@code stellar-api-*.jar} versions are a packaging defect, not a
 * compatibility problem to paper over.</p>
 */
public final class FirstPartyRuntimeModules {

    /** Longest artifact ids first so {@code stellar-parser-mf4} is not read as {@code stellar-parser}. */
    public static final List<String> ARTIFACT_IDS = List.of(
            "stellar-processing-core",
            "stellar-storage-core",
            "stellar-analysis-core",
            "stellar-odx-pdx-core",
            "stellar-parser-spi",
            "stellar-parser-mf4",
            "stellar-parser-asc",
            "stellar-parser-blf",
            "stellar-a2l-core",
            "stellar-dbc-core",
            "stellar-desktop",
            "stellar-domain",
            "stellar-server",
            "stellar-testkit",
            "stellar-api"
    );

    private FirstPartyRuntimeModules() {
    }

    public static String codeSourceLocation(Class<?> type) {
        if (type == null) {
            return "(null)";
        }
        try {
            var domain = type.getProtectionDomain();
            if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
                return "(unknown)";
            }
            return domain.getCodeSource().getLocation().toString();
        } catch (Exception ex) {
            return "(unavailable: " + ex.getMessage() + ")";
        }
    }

    public static String artifactIdFromJarName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) {
            return null;
        }
        if (lower.contains("-sources") || lower.contains("-javadoc") || lower.contains("-tests")) {
            return null;
        }
        for (String artifact : ARTIFACT_IDS) {
            if (lower.equals(artifact + ".jar") || lower.startsWith(artifact + "-")) {
                return artifact;
            }
        }
        return null;
    }

    /**
     * Fails when the same first-party artifact appears more than once on a classpath
     * (different versions or the same file copied into two directories).
     */
    public static void rejectDuplicateJars(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        String sep = System.getProperty("path.separator", ";");
        List<Path> jars = new ArrayList<>();
        for (String raw : classpath.split(Pattern.quote(sep))) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Path.of(raw.trim());
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
            if (artifactIdFromJarName(fileName) != null) {
                jars.add(path);
            }
        }
        rejectDuplicateJars(jars);
    }

    public static void rejectDuplicateJars(List<Path> jars) {
        if (jars == null || jars.isEmpty()) {
            return;
        }
        Map<String, List<String>> byArtifact = new LinkedHashMap<>();
        for (Path jar : jars) {
            if (jar == null) {
                continue;
            }
            String fileName = jar.getFileName() == null ? "" : jar.getFileName().toString();
            String artifact = artifactIdFromJarName(fileName);
            if (artifact == null) {
                continue;
            }
            String location = jar.toAbsolutePath().normalize().toString();
            List<String> locations = byArtifact.computeIfAbsent(artifact, key -> new ArrayList<>());
            if (!containsIgnoreCase(locations, location)) {
                locations.add(location);
            }
        }
        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byArtifact.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.add(entry.getKey() + " -> " + String.join(" | ", entry.getValue()));
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException(
                    "Duplicate first-party StellarAI module JARs on the runtime classpath. "
                            + "A coherent build must ship exactly one copy of each module:\n"
                            + String.join("\n", duplicates));
        }
    }

    public static List<Path> collectJars(Path... dirs) {
        List<Path> jars = new ArrayList<>();
        if (dirs == null) {
            return jars;
        }
        for (Path dir : dirs) {
            collectJarsInto(dir, jars);
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return jars;
    }

    public static Path requireUniqueJar(Path appDir, String artifactId) {
        List<Path> matches = new ArrayList<>();
        for (Path jar : collectJars(appDir, appDir == null ? null : appDir.resolve("lib"))) {
            if (artifactId.equals(artifactIdFromJarName(jar.getFileName().toString()))) {
                matches.add(jar);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("Missing first-party JAR " + artifactId + " under " + appDir);
        }
        if (matches.size() > 1) {
            rejectDuplicateJars(matches);
        }
        return matches.getFirst();
    }

    /**
     * Inspects public methods in a packaged JAR via {@code javap}. This does not load the
     * class into the test JVM, so missing third-party/module dependencies cannot hide a
     * missing method behind {@code NoClassDefFoundError}.
     */
    public static String javapPublic(Path jar, String className) {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalStateException("JAR does not exist: " + jar);
        }
        Path javaHome = Path.of(System.getProperty("java.home", ""));
        Path javap = javaHome.resolve("bin").resolve("javap.exe");
        if (!Files.isRegularFile(javap)) {
            javap = javaHome.resolve("bin").resolve("javap");
        }
        if (!Files.isRegularFile(javap)) {
            throw new IllegalStateException("javap was not found under " + javaHome);
        }
        ProcessBuilder builder = new ProcessBuilder(
                javap.toAbsolutePath().toString(),
                "-classpath",
                jar.toAbsolutePath().normalize().toString(),
                "-public",
                className);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("javap failed for " + className + " in " + jar + ":\n" + output);
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot run javap on " + jar, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("javap interrupted for " + jar, ex);
        }
    }

    public static void requireJavapSignature(Path jar, String className, String signatureFragment) {
        String output = javapPublic(jar, className);
        if (!output.contains(signatureFragment)) {
            throw new IllegalStateException(
                    "Missing " + className + " signature `" + signatureFragment + "` in " + jar + ":\n" + output);
        }
    }

    /**
     * Reads {@code SqliteProjectMetadataStore.currentSchemaVersion()} from packaged bytecode
     * without loading the JAR into this JVM.
     */
    public static int readPackagedSchemaVersion(Path appDir) {
        Path jar = requireUniqueJar(appDir, "stellar-storage-core");
        String output = javapCode(jar, "com.stellar.platform.storage.metadata.SqliteProjectMetadataStore");
        int method = output.indexOf("currentSchemaVersion()");
        if (method < 0) {
            throw new IllegalStateException("currentSchemaVersion() missing from " + jar + ":\n" + output);
        }
        String body = output.substring(method, Math.min(output.length(), method + 500));
        java.util.regex.Matcher bipush = java.util.regex.Pattern.compile("bipush\\s+(\\d+)").matcher(body);
        if (bipush.find()) {
            return Integer.parseInt(bipush.group(1));
        }
        java.util.regex.Matcher sipush = java.util.regex.Pattern.compile("sipush\\s+(\\d+)").matcher(body);
        if (sipush.find()) {
            return Integer.parseInt(sipush.group(1));
        }
        java.util.regex.Matcher iconst = java.util.regex.Pattern.compile("iconst_(\\d)").matcher(body);
        if (iconst.find()) {
            return Integer.parseInt(iconst.group(1));
        }
        throw new IllegalStateException("Cannot read packaged schema version from " + jar + ":\n" + body);
    }

    public static String javapCode(Path jar, String className) {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalStateException("JAR does not exist: " + jar);
        }
        Path javaHome = Path.of(System.getProperty("java.home", ""));
        Path javap = javaHome.resolve("bin").resolve("javap.exe");
        if (!Files.isRegularFile(javap)) {
            javap = javaHome.resolve("bin").resolve("javap");
        }
        if (!Files.isRegularFile(javap)) {
            throw new IllegalStateException("javap was not found under " + javaHome);
        }
        ProcessBuilder builder = new ProcessBuilder(
                javap.toAbsolutePath().toString(),
                "-classpath",
                jar.toAbsolutePath().normalize().toString(),
                "-c",
                "-public",
                className);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("javap -c failed for " + className + " in " + jar + ":\n" + output);
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot run javap -c on " + jar, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("javap interrupted for " + jar, ex);
        }
    }

    private static void collectJarsInto(Path dir, List<Path> jars) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(jars::add);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot list JARs in " + dir, ex);
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}
