package com.stellar.platform.storage;

import java.nio.file.Path;

/**
 * Project-scoped durable paths under the application data home.
 * <ul>
 *   <li>Explicit {@code -Dstellarai.home=...} always wins</li>
 *   <li>Packaged Windows installs ({@code jpackage.app-path} set) use
 *       {@code %LOCALAPPDATA%\StellarAI} for logs and application scratch.
 *       That directory is the install folder, so Analysis Storage selection
 *       stays in {@code ~/.stellarai/config} and is not stored beside the binaries.</li>
 *   <li>Otherwise {@code user.home/.stellarai}</li>
 * </ul>
 * Never hardcodes a drive letter.
 */
public final class StellarAiPaths {

    private StellarAiPaths() {
    }

    public static Path homeRoot() {
        String configured = System.getProperty("stellarai.home", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        if (com.stellar.platform.storage.analysis.AnalysisStorageIsolation.enabled()) {
            return com.stellar.platform.storage.analysis.AnalysisStorageIsolation.processIsolatedHome();
        }
        if (isPackagedLaunch()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "StellarAI").toAbsolutePath().normalize();
            }
        }
        return Path.of(System.getProperty("user.home"), ".stellarai").toAbsolutePath().normalize();
    }

    /**
     * True when launched from a jpackage app-image / installed layout.
     * Property is set by the jpackage launcher; never invent process isolation from this flag.
     */
    public static boolean isPackagedLaunch() {
        String appPath = System.getProperty("jpackage.app-path", "").trim();
        return !appPath.isEmpty();
    }

    public static Path logsRoot() {
        return homeRoot().resolve("logs");
    }

    public static Path configRoot() {
        return homeRoot().resolve("config");
    }

    public static Path projectRoot(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        String cleaned = projectId.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return homeRoot().resolve("projects").resolve(cleaned);
    }

    public static Path signalStoreRoot(String projectId) {
        return projectRoot(projectId).resolve("signal-store");
    }

    /**
     * Stable directory for one processing job's lossless store.
     * Delegates to portable {@link ArtifactStorageRefs}.
     */
    public static Path jobSignalStoreRoot(
            String projectId,
            String sourceId,
            int channelId,
            String definitionId
    ) {
        return ArtifactStorageRefs.jobRoot(projectId, sourceId, channelId, definitionId);
    }
}
