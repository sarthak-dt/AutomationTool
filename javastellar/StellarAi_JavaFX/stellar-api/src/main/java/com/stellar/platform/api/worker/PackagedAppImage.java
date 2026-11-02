package com.stellar.platform.api.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Locates a jpackage APP-IMAGE built for the current product version.
 *
 * <p>Older {@code release/StellarAI-*} trees are left on disk as historical
 * artifacts and must not be selected once the product version has moved on.</p>
 */
public final class PackagedAppImage {

    private PackagedAppImage() {
    }

    public static Optional<Path> locate() {
        String prop = System.getProperty("stellar.packaged.app-image", "").trim();
        if (!prop.isBlank()) {
            Path configured = Path.of(prop).toAbsolutePath().normalize();
            if (isImage(configured)) {
                return Optional.of(rootOf(configured));
            }
        }
        String env = System.getenv("STELLAR_PACKAGED_APP_IMAGE");
        if (env != null && !env.isBlank()) {
            Path configured = Path.of(env).toAbsolutePath().normalize();
            if (isImage(configured)) {
                return Optional.of(rootOf(configured));
            }
        }
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path cursor = start;
        for (int i = 0; i < 8 && cursor != null; i++) {
            Optional<String> version = readProductVersion(cursor);
            if (version.isPresent()) {
                Path qualified = cursor.resolve("release").resolve("StellarAI-" + version.get());
                if (isImage(qualified)) {
                    return Optional.of(qualified);
                }
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    public static boolean present() {
        return locate().isPresent();
    }

    public static Path javaExecutable(Path root) {
        return root.resolve("runtime").resolve("bin").resolve("java.exe");
    }

    public static Path launcher(Path root) {
        return root.resolve("StellarAI.exe");
    }

    public static Path appDirectory(Path root) {
        return rootOf(root).resolve("app");
    }

    static Optional<String> readProductVersion(Path repoRoot) {
        Path props = repoRoot.resolve("stellar-desktop")
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("stellar-version.properties");
        if (!Files.isRegularFile(props)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(props)) {
            properties.load(in);
        } catch (IOException ex) {
            return Optional.empty();
        }
        String version = properties.getProperty("application.version", "").trim();
        return version.isEmpty() ? Optional.empty() : Optional.of(version);
    }

    private static Path rootOf(Path configured) {
        if (Files.isRegularFile(configured) && configured.getFileName().toString().toLowerCase().endsWith(".exe")) {
            return configured.getParent();
        }
        return configured;
    }

    private static boolean isImage(Path root) {
        if (root == null) {
            return false;
        }
        Path dir = rootOf(root);
        return Files.isRegularFile(launcher(dir))
                && Files.isDirectory(dir.resolve("app"))
                && Files.isRegularFile(javaExecutable(dir));
    }
}
