package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata;

import java.util.List;

/** Project-scoped semantic signal mappings. Role is stored as TEXT so later parameters can reuse it. */
public interface SemanticSignalMappingRepository {
    default void upsertSemanticSignalMapping(SemanticSignalMappingMetadata mapping) {
    }

    default List<SemanticSignalMappingMetadata> listSemanticSignalMappings(String projectId) {
        return List.of();
    }

    default List<SemanticSignalMappingMetadata> listSemanticSignalMappings(String projectId, String sourceId) {
        return List.of();
    }

    default void deleteSemanticSignalMappings(String projectId, String sourceId) {
    }

    default void deleteSemanticSignalMappings(String projectId, String sourceId, String configGroup) {
        deleteSemanticSignalMappings(projectId, sourceId);
    }
}
