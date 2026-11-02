package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.AnalysisFindingRow;
import com.stellar.platform.storage.metadata.AnalysisRunMetadata;
import com.stellar.platform.storage.metadata.AnalysisWorkspaceMetadata;

import java.util.List;
import java.util.Optional;

public interface AnalysisRepository {

    default void upsertAnalysisRun(AnalysisRunMetadata run) {
    }

    default Optional<AnalysisRunMetadata> findAnalysisRun(String analysisId) {
        return Optional.empty();
    }

    default List<AnalysisRunMetadata> listAnalysisRuns(String projectId, int limit) {
        return List.of();
    }

    default void replaceAnalysisFindings(String analysisId, List<AnalysisFindingRow> findings) {
    }

    default List<AnalysisFindingRow> listAnalysisFindings(String analysisId) {
        return List.of();
    }

    default void upsertAnalysisWorkspace(AnalysisWorkspaceMetadata workspace) {
    }

    default Optional<AnalysisWorkspaceMetadata> findAnalysisWorkspace(String projectId) {
        return Optional.empty();
    }

    default void upsertDerivedSignalDefinition(
            com.stellar.platform.storage.metadata.DerivedSignalDefinitionMetadata definition
    ) {
    }

    default Optional<com.stellar.platform.storage.metadata.DerivedSignalDefinitionMetadata>
    findDerivedSignalDefinition(String projectId, String identity) {
        return Optional.empty();
    }
}
