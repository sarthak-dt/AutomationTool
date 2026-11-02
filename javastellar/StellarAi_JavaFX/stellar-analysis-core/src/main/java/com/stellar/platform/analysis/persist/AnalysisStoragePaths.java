package com.stellar.platform.analysis.persist;

import java.nio.file.Path;

/**
 * Analysis artifacts live under configured Analysis Storage, never beside user MF4 files.
 *
 * {@code <projectArtifactsRoot>/analysis/<analysisId>/}
 */
public final class AnalysisStoragePaths {

    private AnalysisStoragePaths() {
    }

    public static Path analysisRoot(Path projectArtifactsRoot, String analysisId) {
        Path home = projectArtifactsRoot == null ? Path.of("analysis") : projectArtifactsRoot;
        return home.resolve("analysis").resolve(safe(analysisId));
    }

    public static Path stagingRoot(Path projectArtifactsRoot, String analysisId) {
        return analysisRoot(projectArtifactsRoot, analysisId).resolveSibling(safe(analysisId) + ".staging");
    }

    public static Path manifest(Path root) {
        return root.resolve("manifest.json");
    }

    public static Path findings(Path root) {
        return root.resolve("findings.json");
    }

    public static Path derivedSeries(Path root) {
        return root.resolve("derived-series");
    }

    public static Path spectrum(Path root) {
        return root.resolve("spectrum.csv");
    }

    public static Path psd(Path root) {
        return root.resolve("psd.csv");
    }

    public static Path peaks(Path root) {
        return root.resolve("peaks.csv");
    }

    public static Path lagSummary(Path root) {
        return root.resolve("lag.csv");
    }

    public static Path cycles(Path root) {
        return root.resolve("cycles.csv");
    }

    public static Path advancedManifest(Path root) {
        return root.resolve("advanced.json");
    }

    private static String safe(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        return id.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
