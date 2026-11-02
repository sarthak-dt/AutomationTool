package com.stellar.platform.storage.analysis;

import com.stellar.platform.storage.StellarAiPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the controlled Analysis Storage home used for project databases and
 * heavy signal artifacts.
 *
 * <p>Layout: {@code <userSelectedRoot>/StellarAIAnalysis/projects/<projectId>/}
 * Metadata lives in one central {@code database/stellar.db}. Heavy artifacts
 * remain under {@code projects/<projectId>/}.
 * Explicit {@code newProject(name, projectRoot)} paths are unchanged.
 */
public final class AnalysisStorageRoots {

    public static final String CONTROLLED_DIR_NAME = "StellarAIAnalysis";
    /** Read by MF4 spill code. Set only while a selected Analysis Storage root is installed. */
    public static final String RUNTIME_TEMP_PROPERTY = "stellarai.runtime.temp";

    private static volatile Path userSelectedRoot;

    private AnalysisStorageRoots() {
    }

    /** Installs the user-selected directory (parent of {@link #CONTROLLED_DIR_NAME}). */
    public static void setUserSelectedRoot(Path root) {
        if (root != null) {
            AnalysisStorageIsolation.refuseOperatorWorkspace(root);
        }
        userSelectedRoot = root == null ? null : root.toAbsolutePath().normalize();
        publishRuntimeTemp();
    }

    public static void clear() {
        userSelectedRoot = null;
        System.clearProperty(RUNTIME_TEMP_PROPERTY);
    }

    public static Optional<Path> userSelectedRoot() {
        Path root = userSelectedRoot;
        return root == null ? Optional.empty() : Optional.of(root);
    }

    /**
     * Controlled StellarAI-owned directory under the user selection.
     * Empty when Analysis Storage has not been configured.
     */
    public static Optional<Path> controlledRoot() {
        return userSelectedRoot().map(root -> root.resolve(CONTROLLED_DIR_NAME).toAbsolutePath().normalize());
    }

    /**
     * Home used to resolve portable {@code projects/...} artifact refs.
     * Falls back to {@link com.stellar.platform.storage.StellarAiPaths#homeRoot()} only when
     * no Analysis Storage is configured (tests / legacy).
     */
    public static Path artifactHome() {
        return controlledRoot().orElseGet(com.stellar.platform.storage.StellarAiPaths::homeRoot);
    }

    public static Path requireControlledRoot() {
        return controlledRoot().orElseThrow(() ->
                new IllegalStateException("Analysis Storage is not configured"));
    }

    public static boolean isConfigured() {
        return userSelectedRoot != null;
    }

    public static Path databaseRoot() {
        return child("database");
    }

    public static Path projectsRoot() {
        return child("projects");
    }

    public static Path logsRoot() {
        return child("logs");
    }

    public static Path cacheRoot() {
        return child("cache");
    }

    public static Path tempRoot() {
        return child("temp");
    }

    public static Path configRoot() {
        return child("config");
    }

    /** Application preference file under the configured Analysis Storage config directory. */
    public static Path uiPreferencesFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        return configRoot().resolve(fileName);
    }

    public static Path signalStoreRoot(String projectId) {
        return projectArtifactsRoot(projectId).resolve("signal-store");
    }

    public static Path ensureControlledDirectory() {
        Path controlled = requireControlledRoot();
        try {
            Files.createDirectories(controlled);
            Files.createDirectories(databaseRoot());
            Files.createDirectories(projectsRoot());
            Files.createDirectories(logsRoot());
            Files.createDirectories(cacheRoot());
            Files.createDirectories(tempRoot());
            Files.createDirectories(configRoot());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Analysis Storage is unavailable: " + controlled, ex);
        }
        return controlled;
    }

    private static Path child(String name) {
        return requireControlledRoot().resolve(name).toAbsolutePath().normalize();
    }

    /**
     * Points spill/temp at the controlled directory only while that directory exists.
     * An invalid root must not keep {@code stellarai.runtime.temp} aimed at a file.
     */
    public static void refreshRuntimeTempBinding() {
        publishRuntimeTemp();
    }

    private static void publishRuntimeTemp() {
        if (userSelectedRoot == null) {
            System.clearProperty(RUNTIME_TEMP_PROPERTY);
            return;
        }
        Path controlled = userSelectedRoot.resolve(CONTROLLED_DIR_NAME).toAbsolutePath().normalize();
        if (!Files.isDirectory(controlled)) {
            System.clearProperty(RUNTIME_TEMP_PROPERTY);
            return;
        }
        System.setProperty(RUNTIME_TEMP_PROPERTY, controlled.resolve("temp").toString());
    }

    public static Path projectArtifactsRoot(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        String cleaned = projectId.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (isConfigured()) {
            return projectsRoot().resolve(cleaned);
        }
        return artifactHome().resolve("projects").resolve(cleaned);
    }

    /**
     * Directory that owns a project's {@code .stellar/stellar.db} when the caller
     * did not supply an explicit project root.
     *
     * <p>Configured Analysis Storage: {@code <userRoot>/StellarAIAnalysis/projects/<id>}.
     * Otherwise the application-home fallback {@link StellarAiPaths#projectRoot(String)}.
     */
    public static Path defaultManagedProjectRoot(String projectId) {
        if (isConfigured()) {
            return projectArtifactsRoot(projectId);
        }
        return StellarAiPaths.projectRoot(projectId);
    }

    /** {@code .../StellarAIAnalysis/projects} when Analysis Storage is configured. */
    public static Optional<Path> configuredProjectsDirectory() {
        return controlledRoot().map(root -> root.resolve("projects").toAbsolutePath().normalize());
    }
}
