package com.stellar.platform.storage;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Portable artifact storage references independent of drive letters / OS roots.
 * <p>
 * Logical form: {@code projects/<projectId>/signal-store/<sourceId>/ch-<n>/<definitionId>}
 * Absolute paths written by schema v3 remain readable for backward compatibility.
 */
public final class ArtifactStorageRefs {

    private ArtifactStorageRefs() {
    }

    public static String forJob(String projectId, String sourceId, int channelId, String definitionId) {
        String def = definitionId == null || definitionId.isBlank() ? "_none_" : sanitize(definitionId);
        return "projects/" + sanitize(projectId)
                + "/signal-store/" + sanitize(sourceId)
                + "/ch-" + channelId
                + "/" + def;
    }

    public static boolean isLogicalRef(String value) {
        return value != null && value.startsWith("projects/") && !looksAbsolute(value);
    }

    public static Path resolveLocal(String storageRefOrAbsolute) {
        Objects.requireNonNull(storageRefOrAbsolute, "storageRefOrAbsolute");
        String value = storageRefOrAbsolute.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("storage ref must not be blank");
        }
        if (isLogicalRef(value)) {
            return com.stellar.platform.storage.analysis.AnalysisStorageRoots.artifactHome()
                    .resolve(value.replace('/', java.io.File.separatorChar))
                    .toAbsolutePath().normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    public static Path jobRoot(String projectId, String sourceId, int channelId, String definitionId) {
        return resolveLocal(forJob(projectId, sourceId, channelId, definitionId));
    }

    private static boolean looksAbsolute(String value) {
        if (value.length() >= 2 && value.charAt(1) == ':') return true; // Windows drive
        return value.startsWith("/") || value.startsWith("\\\\");
    }

    private static String sanitize(String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("path segment became empty after sanitization");
        }
        return cleaned;
    }
}
