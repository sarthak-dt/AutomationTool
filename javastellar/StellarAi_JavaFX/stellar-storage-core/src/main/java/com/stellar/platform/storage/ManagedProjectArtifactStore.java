package com.stellar.platform.storage;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Safe ownership of StellarAI-managed project artifact directories.
 * Never touches original MF4/ASC/BLF/DBC paths outside the managed root.
 */
public final class ManagedProjectArtifactStore {

    public record DeleteResult(Path projectRoot, boolean removed, List<String> warnings) {
        public DeleteResult {
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    /** Absolute managed project root for {@code projectId}. */
    public Path projectRoot(String projectId) {
        return AnalysisStorageRoots.defaultManagedProjectRoot(projectId);
    }

    public Path managedProjectsRoot() {
        return AnalysisStorageRoots.configuredProjectsDirectory()
                .orElseGet(() -> StellarAiPaths.homeRoot().resolve("projects").toAbsolutePath().normalize());
    }

    /** True only when {@code candidate} is strictly under a StellarAI-owned projects tree. */
    public boolean isManagedProjectPath(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (isStrictChild(homeProjectsRoot(), normalized)) {
            return true;
        }
        return AnalysisStorageRoots.configuredProjectsDirectory()
                .filter(root -> isStrictChild(root, normalized))
                .isPresent();
    }

    /**
     * Deletes the project's managed folder under Analysis Storage and/or
     * {@code ~/.stellarai/projects/<projectId>/}. Does not delete original
     * measurement or definition files.
     */
    public DeleteResult deleteProjectArtifacts(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        List<String> warnings = new ArrayList<>();
        boolean removed = true;
        Path lastRoot = projectRoot(projectId).toAbsolutePath().normalize();
        com.stellar.platform.storage.analysis.AnalysisStorageIsolation.refuseOperatorWorkspace(lastRoot);
        for (Path root : managedRootsFor(projectId)) {
            com.stellar.platform.storage.analysis.AnalysisStorageIsolation.refuseOperatorWorkspace(root);
            lastRoot = root;
            if (!isManagedProjectPath(root)) {
                warnings.add("Refused to delete non-managed path for project " + projectId);
                removed = false;
                continue;
            }
            if (!Files.exists(root)) {
                continue;
            }
            try {
                deleteRecursively(root);
            } catch (IOException ex) {
                warnings.add("Could not fully remove managed artifacts at " + root + ": " + ex.getMessage());
                removed = false;
            }
        }
        return new DeleteResult(lastRoot, removed, warnings);
    }

    private static Path homeProjectsRoot() {
        return StellarAiPaths.homeRoot().resolve("projects").toAbsolutePath().normalize();
    }

    private static List<Path> managedRootsFor(String projectId) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(StellarAiPaths.projectRoot(projectId).toAbsolutePath().normalize());
        if (AnalysisStorageRoots.isConfigured()) {
            roots.add(AnalysisStorageRoots.projectArtifactsRoot(projectId).toAbsolutePath().normalize());
        }
        return List.copyOf(roots);
    }

    private static boolean isStrictChild(Path parent, Path candidate) {
        Path root = parent.toAbsolutePath().normalize();
        return candidate.startsWith(root) && !candidate.equals(root);
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
