package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SignalProvenance;

import java.util.List;
import java.util.Optional;

/** Processing runs, lossless artifact manifests, and signal-catalog metadata (never samples). */
public interface ProcessingRepository {
    void upsertProcessingRun(ProcessingRunMetadata run);

    void upsertProcessingArtifact(ProcessingArtifactMetadata artifact);

    void markProcessingArtifactsStatus(String projectId, String status);

    void deleteProcessingArtifacts(String projectId);

    default Optional<ProcessingArtifactMetadata> findValidArtifact(
            String projectId,
            String sourceId,
            int channelId,
            String definitionId
    ) {
        return Optional.empty();
    }

    default void replaceSignalCatalog(String projectId, String artifactId, List<SignalCatalogMetadata> entries) {
    }

    default List<SignalCatalogMetadata> listSignalCatalog(String projectId) {
        return List.of();
    }

    default List<SignalCatalogMetadata> searchSignalCatalog(String projectId, String query) {
        return List.of();
    }

    default Optional<SignalProvenance> getSignalProvenance(String projectId, String signalCatalogId) {
        return Optional.empty();
    }
}
