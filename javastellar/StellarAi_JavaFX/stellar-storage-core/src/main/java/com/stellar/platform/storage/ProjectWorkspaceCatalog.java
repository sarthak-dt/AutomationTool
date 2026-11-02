package com.stellar.platform.storage;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectNameNormalizer;
import com.stellar.platform.storage.metadata.ProjectSummaryAvailability;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers independent project-local databases and builds dashboard summaries
 * without opening a project workspace or loading signal samples.
 *
 * <p>Each project-local SQLite connection is short-lived. The legacy global
 * metadata DB is read only as a fallback when a project has no usable local file.</p>
 */
public final class ProjectWorkspaceCatalog {

    private static final System.Logger LOG = System.getLogger(ProjectWorkspaceCatalog.class.getName());

    private ProjectWorkspaceCatalog() {
    }

    public static Path managedProjectsDirectory() {
        return StellarAiPaths.homeRoot().resolve("projects");
    }

    public static List<ProjectMetadata> listManagedProjects() {
        LinkedHashMap<String, ProjectMetadata> byId = new LinkedHashMap<>();
        for (ProjectDashboardSummary summary : listManagedDashboardSummaries()) {
            byId.putIfAbsent(summary.projectId(), new ProjectMetadata(
                    summary.projectId(), summary.name(), summary.createdAt(), summary.updatedAt()));
        }
        return List.copyOf(byId.values());
    }

    /**
     * One lightweight summary per registered saved project. Never hydrates samples,
     * never keeps project SQLite connections open after return.
     */
    public static List<ProjectDashboardSummary> listManagedDashboardSummaries() {
        LinkedHashMap<String, ProjectDashboardSummary> byId = new LinkedHashMap<>();
        collectLocalSummaries(byId);
        mergeLegacySummaries(byId);
        List<ProjectDashboardSummary> out = new ArrayList<>(byId.values());
        out.sort(Comparator
                .comparing(ProjectDashboardSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProjectDashboardSummary::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static void collectLocalSummaries(Map<String, ProjectDashboardSummary> byId) {
        // Prefer the configured Analysis Storage tree so dashboard rows match where
        // new project databases are created.
        AnalysisStorageRoots.configuredProjectsDirectory().ifPresent(dir -> scanProjectDirectory(dir, byId));
        scanProjectDirectory(managedProjectsDirectory(), byId);
        for (Map.Entry<String, Path> indexed : List.copyOf(ProjectRootIndex.entries().entrySet())) {
            if (byId.containsKey(indexed.getKey()) && byId.get(indexed.getKey()).countsAvailable()) {
                continue;
            }
            readLocalSummary(indexed.getValue(), indexed.getKey(), byId);
        }
    }

    private static void scanProjectDirectory(Path managed, Map<String, ProjectDashboardSummary> byId) {
        if (managed == null || !Files.isDirectory(managed)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(managed, Files::isDirectory)) {
            for (Path projectRoot : stream) {
                String folderId = projectRoot.getFileName().toString();
                // Artifact-only UUID folders and leftover deletes have no project-local DB.
                if (!ProjectStoragePaths.of(projectRoot).databaseExists()) {
                    continue;
                }
                readLocalSummary(projectRoot, folderId, byId);
            }
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "Cannot scan managed projects directory: " + ex.getMessage());
        }
    }

    /**
     * Lists a directory as a user project only when its local SQLite contains a matching
     * {@code projects} row with a real display name. Missing/orphan registry entries and
     * artifact UUID directories are not reconstructed as projects.
     */
    private static void readLocalSummary(
            Path projectRoot,
            String expectedProjectId,
            Map<String, ProjectDashboardSummary> byId
    ) {
        if (projectRoot == null || expectedProjectId == null || expectedProjectId.isBlank()) {
            return;
        }
        if (byId.containsKey(expectedProjectId) && byId.get(expectedProjectId).countsAvailable()) {
            return;
        }
        ProjectStoragePaths paths = ProjectStoragePaths.of(projectRoot);
        if (!paths.isLocatedUnderProjectRoot(paths.databasePath())) {
            LOG.log(System.Logger.Level.WARNING,
                    "Rejected database path outside project root for " + expectedProjectId
                            + ": " + paths.databasePath());
            ProjectRootIndex.unregister(expectedProjectId);
            return;
        }
        if (!paths.databaseExists()) {
            LOG.log(System.Logger.Level.INFO,
                    "Orphan project-root index entry (no metadata DB) for " + expectedProjectId
                            + " at " + paths.databasePath());
            ProjectRootIndex.unregister(expectedProjectId);
            return;
        }
        if (!looksLikeSqlite(paths.databasePath())) {
            LOG.log(System.Logger.Level.WARNING,
                    "Project-local metadata file is not a SQLite database for "
                            + expectedProjectId + " at " + paths.databasePath());
            ProjectRootIndex.unregister(expectedProjectId);
            return;
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(paths.databasePath())) {
            Optional<ProjectDashboardSummary> match = store.listProjectDashboardSummaries().stream()
                    .filter(row -> expectedProjectId.equals(row.projectId()))
                    .findFirst();
            if (match.isPresent()) {
                byId.put(expectedProjectId, match.get().locatedAt(projectRoot, ProjectSummaryAvailability.AVAILABLE));
                return;
            }
            LOG.log(System.Logger.Level.INFO,
                    "Orphan project directory has no projects row for " + expectedProjectId
                            + " at " + paths.databasePath());
            ProjectRootIndex.unregister(expectedProjectId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Cannot read project-local DB for " + expectedProjectId
                            + " at " + paths.databasePath() + ": " + ex.getMessage());
        }
    }

    private static void mergeLegacySummaries(Map<String, ProjectDashboardSummary> byId) {
        for (Path legacy : SqliteProjectMetadataStore.legacyDatabaseCandidates()) {
            if (!Files.isRegularFile(legacy) || !looksLikeSqlite(legacy)) {
                continue;
            }
            try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(legacy)) {
                for (ProjectDashboardSummary row : store.listProjectDashboardSummaries()) {
                    ProjectDashboardSummary existing = byId.get(row.projectId());
                    if (existing == null) {
                        LOG.log(System.Logger.Level.INFO,
                                "Dashboard using legacy metadata DB for project " + row.projectId()
                                        + " (" + row.name() + ")");
                        byId.put(row.projectId(), row.locatedAt(null, ProjectSummaryAvailability.LEGACY));
                        continue;
                    }
                    if (!existing.countsAvailable()) {
                        byId.put(row.projectId(), row.locatedAt(
                                existing.projectRoot().isBlank() ? null : Path.of(existing.projectRoot()),
                                ProjectSummaryAvailability.LEGACY));
                        continue;
                    }
                    if (!existing.hasPersistedContent() && row.hasPersistedContent()) {
                        LOG.log(System.Logger.Level.INFO,
                                "Project-local DB for " + row.projectId()
                                        + " has no inventory; using legacy metadata counts");
                        byId.put(row.projectId(), row.locatedAt(
                                existing.projectRoot().isBlank() ? null : Path.of(existing.projectRoot()),
                                ProjectSummaryAvailability.LEGACY));
                    }
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read legacy metadata DB " + legacy + ": " + ex.getMessage());
            }
        }
    }

    static boolean looksLikeSqlite(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (var in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(16);
            return header.length >= 16 && new String(header, java.nio.charset.StandardCharsets.US_ASCII)
                    .startsWith("SQLite format 3");
        } catch (IOException ex) {
            return false;
        }
    }

    public static Optional<ProjectMetadata> findByNormalizedName(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return Optional.empty();
        }
        String needle = ProjectNameNormalizer.normalized(normalizedName);
        return listManagedProjects().stream()
                .filter(p -> needle.equals(ProjectNameNormalizer.normalized(p.name())))
                .findFirst();
    }

    public static boolean existsProjectName(String name, String excludeProjectId) {
        try {
            String display = ProjectNameNormalizer.requireDisplayName(name);
            return findByNormalizedName(ProjectNameNormalizer.normalized(display))
                    .filter(p -> excludeProjectId == null || !excludeProjectId.equals(p.projectId()))
                    .isPresent();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static Optional<ProjectStoragePaths> locateManagedProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        Optional<ProjectStoragePaths> indexed = ProjectRootIndex.locate(projectId);
        if (indexed.isPresent()) {
            return indexed;
        }
        if (AnalysisStorageRoots.isConfigured()) {
            ProjectStoragePaths configured = ProjectStoragePaths.of(
                    AnalysisStorageRoots.projectArtifactsRoot(projectId));
            if (configured.databaseExists()) {
                return Optional.of(configured);
            }
        }
        ProjectStoragePaths home = ProjectStoragePaths.of(StellarAiPaths.projectRoot(projectId));
        return home.databaseExists() ? Optional.of(home) : Optional.empty();
    }
}
