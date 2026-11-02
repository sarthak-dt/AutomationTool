package com.stellar.platform.analysis.persist;

import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Staging + atomic directory commit. Cancelled or failed runs never leave COMPLETED artifacts.
 */
public final class AnalysisArtifactStore {

    public Path commit(Path projectArtifactsRoot, AnalysisResult result) {
        if (result == null || !result.status().successful()) {
            return null;
        }
        Path staging = AnalysisStoragePaths.stagingRoot(projectArtifactsRoot, result.analysisId());
        Path dest = AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, result.analysisId());
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            Files.writeString(AnalysisStoragePaths.manifest(staging), manifestJson(result), StandardCharsets.UTF_8);
            Files.writeString(AnalysisStoragePaths.findings(staging), findingsJson(result.findings()), StandardCharsets.UTF_8);
            deleteTree(dest);
            Files.createDirectories(dest.getParent());
            try {
                Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileSystemException ex) {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest;
        } catch (IOException e) {
            deleteTree(staging);
            throw new IllegalStateException("Cannot commit analysis artifact", e);
        }
    }

    public void discardStaging(Path projectArtifactsRoot, String analysisId) {
        deleteTree(AnalysisStoragePaths.stagingRoot(projectArtifactsRoot, analysisId));
    }

    public boolean hasCommitted(Path projectArtifactsRoot, String analysisId) {
        return Files.isRegularFile(AnalysisStoragePaths.manifest(
                AnalysisStoragePaths.analysisRoot(projectArtifactsRoot, analysisId)));
    }

    private static String manifestJson(AnalysisResult result) {
        return "{\"analysisId\":\"" + esc(result.analysisId())
                + "\",\"status\":\"" + result.status()
                + "\",\"version\":\"" + esc(result.analysisVersion())
                + "\",\"alignment\":\"" + result.alignmentPolicy()
                + "\",\"message\":\"" + esc(result.message())
                + "\"}";
    }

    private static String findingsJson(List<EngineeringFinding> findings) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < findings.size(); i++) {
            EngineeringFinding f = findings.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(esc(f.findingId()))
                    .append("\",\"type\":\"").append(f.findingType())
                    .append("\",\"summary\":\"").append(esc(f.summary()))
                    .append("\",\"start\":").append(f.startTimestampNanos())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
