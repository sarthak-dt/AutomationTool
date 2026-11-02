package com.stellar.platform.analysis.investigation;

import java.nio.file.Path;

public final class InvestigationStoragePaths {

    private InvestigationStoragePaths() {
    }

    public static Path root(Path projectArtifactsRoot, String investigationId) {
        Path home = projectArtifactsRoot == null ? Path.of("investigations") : projectArtifactsRoot;
        return home.resolve("investigations").resolve(safe(investigationId));
    }

    public static Path snapshot(Path projectArtifactsRoot, String investigationId, String snapshotId) {
        return root(projectArtifactsRoot, investigationId).resolve("snapshots")
                .resolve(safe(snapshotId) + ".png");
    }

    /** Numeric route summary stored beside the snapshot. Contains no credentials. */
    public static Path routeMapMetadata(Path projectArtifactsRoot, String investigationId, String snapshotId) {
        return snapshot(projectArtifactsRoot, investigationId, snapshotId)
                .resolveSibling(safe(snapshotId) + ".route.json");
    }

    public static final String ROUTE_MAP_ASSET = "map-route.png";

    public static Path reportDir(Path projectArtifactsRoot, String investigationId, String reportId) {
        return root(projectArtifactsRoot, investigationId).resolve("reports").resolve(safe(reportId));
    }

    public static Path reportStaging(Path projectArtifactsRoot, String investigationId, String reportId) {
        return reportDir(projectArtifactsRoot, investigationId, reportId + ".staging");
    }

    public static Path packageStaging(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".staging");
    }

    public static String safe(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        return id.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
