package com.stellar.platform.storage;

import com.stellar.platform.storage.api.ProjectRepository;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-neutral project deletion by {@code project_id}.
 * Deletes SQLite/PostgreSQL metadata first, then managed artifact directories.
 * Never deletes original MF4/ASC/BLF/DBC source files.
 */
public final class ProjectDeletionService {

    public record Result(
            String projectId,
            String projectName,
            boolean metadataDeleted,
            boolean artifactsRemoved,
            List<String> warnings
    ) {
        public Result {
            projectId = Objects.requireNonNull(projectId, "projectId");
            projectName = projectName == null ? "" : projectName;
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public boolean success() {
            return metadataDeleted;
        }
    }

    private final ProjectRepository projects;
    private final ManagedProjectArtifactStore artifacts;

    public ProjectDeletionService(ProjectRepository projects, ManagedProjectArtifactStore artifacts) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public Result deleteByProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        Optional<ProjectMetadata> existing = projects.listProjects().stream()
                .filter(p -> projectId.equals(p.projectId()))
                .findFirst();
        // Prefer load for name if list is large — loadProject is authoritative for existence.
        String name = existing.map(ProjectMetadata::name).orElse("");
        var snapshot = projects.loadProject(projectId);
        if (snapshot.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            warnings.add("Project was not found in metadata: " + projectId);
            // Still attempt managed artifact cleanup for orphaned directories.
            var art = artifacts.deleteProjectArtifacts(projectId);
            warnings.addAll(art.warnings());
            return new Result(projectId, name, false, art.removed(), warnings);
        }
        name = snapshot.get().project().name();

        projects.deleteProject(projectId);

        var art = artifacts.deleteProjectArtifacts(projectId);
        List<String> warnings = new ArrayList<>(art.warnings());
        if (!art.removed()) {
            warnings.add("Project metadata was removed, but some managed artifacts may remain under "
                    + art.projectRoot());
        }
        return new Result(projectId, name, true, art.removed(), warnings);
    }

    /**
     * Production project-local SQLite: close the target database before deleting
     * the managed folder so Windows can remove {@code stellar.db} / WAL files.
     * {@code databasePath} may be null when only orphaned artifact directories remain.
     */
    public static Result deleteLocatedProject(String projectId, Path databasePath) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        String name = "";
        boolean metadataDeleted = false;
        List<String> warnings = new ArrayList<>();
        if (databasePath != null && Files.isRegularFile(databasePath)) {
            try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(databasePath)) {
                Optional<SavedProjectSnapshot> snapshot = store.loadProject(projectId);
                if (snapshot.isPresent()) {
                    name = snapshot.get().project().name();
                    store.deleteProject(projectId);
                    metadataDeleted = true;
                } else {
                    warnings.add("Project was not found in metadata: " + projectId);
                }
            } catch (RuntimeException ex) {
                warnings.add("Could not delete project metadata: "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        } else {
            warnings.add("Project-local metadata DB missing for " + projectId);
        }
        ManagedProjectArtifactStore artifacts = new ManagedProjectArtifactStore();
        var art = artifacts.deleteProjectArtifacts(projectId);
        warnings.addAll(art.warnings());
        if (metadataDeleted && !art.removed()) {
            warnings.add("Project metadata was removed, but some managed artifacts may remain under "
                    + art.projectRoot());
        }
        return new Result(projectId, name, metadataDeleted, art.removed(), warnings);
    }
}
