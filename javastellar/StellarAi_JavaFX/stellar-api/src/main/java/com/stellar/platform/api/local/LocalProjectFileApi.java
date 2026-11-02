package com.stellar.platform.api.local;

import com.stellar.platform.api.ProjectFileApi;
import com.stellar.platform.api.dto.ProjectFileDto;
import com.stellar.platform.api.dto.RegisterFileRequest;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LocalProjectFileApi implements ProjectFileApi {

    private final MetadataStoreFactory stores;

    public LocalProjectFileApi(MetadataStoreFactory stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public ProjectFileDto registerFile(String projectId, RegisterFileRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        Path path = Path.of(request.path()).toAbsolutePath().normalize();
        String fileId = request.fileId().isBlank() ? UUID.randomUUID().toString() : request.fileId();
        long size = request.sizeBytes() > 0
                ? request.sizeBytes()
                : (Files.isRegularFile(path) ? path.toFile().length() : 0L);
        ProjectFileMetadata metadata = new ProjectFileMetadata(
                projectId,
                fileId,
                path.toString(),
                request.kind(),
                size,
                request.validationStatus(),
                request.validationMessage(),
                null,
                "",
                false,
                "MEASUREMENT".equalsIgnoreCase(request.kind()),
                "DEFINITION".equalsIgnoreCase(request.kind()) || "DBC".equalsIgnoreCase(request.kind())
        );
        stores.store().upsertFile(metadata);
        return toDto(metadata);
    }

    @Override
    public List<ProjectFileDto> listFiles(String projectId) {
        return stores.store().loadProject(projectId)
                .map(SavedProjectSnapshot::files)
                .orElse(List.of())
                .stream()
                .map(LocalProjectFileApi::toDto)
                .toList();
    }

    @Override
    public void deleteFile(String projectId, String fileId) {
        stores.store().deleteFile(projectId, fileId);
    }

    private static ProjectFileDto toDto(ProjectFileMetadata metadata) {
        return new ProjectFileDto(
                metadata.projectId(),
                metadata.fileId(),
                metadata.path(),
                metadata.kind(),
                metadata.sizeBytes(),
                metadata.validationStatus(),
                metadata.validationMessage(),
                metadata.associatedDefinitionId() == null ? "" : metadata.associatedDefinitionId(),
                metadata.selectedMeasurement(),
                metadata.selectedDefinition()
        );
    }
}
