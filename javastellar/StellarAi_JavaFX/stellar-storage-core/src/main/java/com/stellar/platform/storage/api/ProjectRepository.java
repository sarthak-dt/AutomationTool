package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Database-neutral project lifecycle. Implementations: SQLite today, PostgreSQL later.
 * Must never persist decoded waveform samples.
 */
public interface ProjectRepository extends AutoCloseable {
    void upsertProject(ProjectMetadata project);

    List<ProjectMetadata> listProjects();

    /**
     * Lightweight per-project counts for Dashboard/Recent Projects.
     * Default derives zeroed rows from {@link #listProjects()} — implementations should
     * replace with cheap SQL aggregations (no waveform or inventory hydration).
     */
    default List<ProjectDashboardSummary> listProjectDashboardSummaries() {
        return listProjects().stream()
                .map(p -> new ProjectDashboardSummary(
                        p.projectId(), p.name(), p.createdAt(), p.updatedAt(), 0, 0, 0, ""))
                .toList();
    }

    Optional<SavedProjectSnapshot> loadProject(String projectId);

    void upsertFile(ProjectFileMetadata file);

    void deleteFile(String projectId, String fileId);

    /**
     * True when another project already owns this display name
     * (trim + case-insensitive). {@code excludeProjectId} may be null.
     */
    default boolean existsProjectName(String name, String excludeProjectId) {
        return findByNormalizedName(
                com.stellar.platform.storage.metadata.ProjectNameNormalizer.normalized(name))
                .filter(p -> excludeProjectId == null || !excludeProjectId.equals(p.projectId()))
                .isPresent();
    }

    default Optional<ProjectMetadata> findByNormalizedName(String normalizedName) {
        return Optional.empty();
    }

    /** Deletes project metadata and cascaded child rows by project_id. Not by name. */
    default void deleteProject(String projectId) {
        throw new UnsupportedOperationException("deleteProject is not supported by this store");
    }

    @Override
    default void close() {
    }
}
