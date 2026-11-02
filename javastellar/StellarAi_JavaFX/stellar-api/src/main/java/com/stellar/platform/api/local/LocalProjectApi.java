package com.stellar.platform.api.local;

import com.stellar.platform.api.ProjectApi;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.dto.ProjectSummaryDto;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LocalProjectApi implements ProjectApi {

    private final MetadataStoreFactory stores;

    public LocalProjectApi(MetadataStoreFactory stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public ProjectDto createProject(CreateProjectRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = Instant.now();
        String projectId = request.projectId().isBlank()
                ? PlatformIds.newId()
                : PlatformIds.requireId("projectId", request.projectId());
        Optional<ProjectDto> existing = getProject(projectId);
        if (existing.isPresent()) {
            // Same logical project id across Local/HTTP — idempotent ensure.
            return existing.get();
        }
        Path root = request.projectRoot().isBlank()
                ? stores.projectRoot(projectId)
                : Path.of(request.projectRoot());
        stores.rememberProjectRoot(projectId, root);
        ProjectMetadata metadata = new ProjectMetadata(projectId, request.name(), now, now, "");
        stores.store().upsertProject(metadata);
        return toDto(metadata, root.toString());
    }

    @Override
    public Optional<ProjectDto> getProject(String projectId) {
        Optional<ProjectDto> loaded = stores.store().loadProject(projectId)
                .map(SavedProjectSnapshot::project)
                .map(p -> toDto(p, stores.projectRoot(projectId).toString()));
        if (loaded.isPresent()) {
            return loaded;
        }
        return stores.store().listProjects().stream()
                .filter(p -> p.projectId().equals(projectId))
                .findFirst()
                .map(p -> toDto(p, stores.projectRoot(projectId).toString()));
    }

    @Override
    public List<ProjectSummaryDto> listProjects() {
        ProjectMetadataStore store = stores.store();
        List<ProjectDashboardSummary> summaries = store.listProjectDashboardSummaries();
        if (!summaries.isEmpty()) {
            return summaries.stream()
                    .map(s -> new ProjectSummaryDto(
                            s.projectId(),
                            s.name(),
                            s.createdAt(),
                            s.updatedAt(),
                            s.measurementFileCount(),
                            s.definitionFileCount(),
                            s.signalCount(),
                            s.lastProcessingStatus(),
                            s.projectRoot().isBlank()
                                    ? stores.projectRoot(s.projectId()).toString()
                                    : s.projectRoot()
                    ))
                    .toList();
        }
        return store.listProjects().stream()
                .map(p -> new ProjectSummaryDto(
                        p.projectId(),
                        p.name(),
                        p.createdAt(),
                        p.updatedAt(),
                        0,
                        0,
                        0,
                        "",
                        stores.projectRoot(p.projectId()).toString()
                ))
                .toList();
    }

    @Override
    public void closeProject(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
    }

    private static ProjectDto toDto(ProjectMetadata metadata, String projectRoot) {
        return new ProjectDto(
                metadata.projectId(),
                metadata.name(),
                metadata.createdAt(),
                metadata.updatedAt(),
                metadata.lastWorkspace(),
                projectRoot
        );
    }
}
