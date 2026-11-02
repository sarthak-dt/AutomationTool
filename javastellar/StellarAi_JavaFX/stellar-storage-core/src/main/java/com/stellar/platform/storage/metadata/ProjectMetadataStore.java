package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.storage.api.AuditRepository;
import com.stellar.platform.storage.api.EngineeringBookmarkRepository;
import com.stellar.platform.storage.api.ExportJobRepository;
import com.stellar.platform.storage.api.GraphAnnotationRepository;
import com.stellar.platform.storage.api.GraphInvestigationRepository;
import com.stellar.platform.storage.api.InventoryRepository;
import com.stellar.platform.storage.api.MapRouteStateRepository;
import com.stellar.platform.storage.api.SemanticSignalMappingRepository;
import com.stellar.platform.storage.api.ProcessingLedgerRepository;
import com.stellar.platform.storage.api.ProcessingRepository;
import com.stellar.platform.storage.api.ProjectRepository;

import java.util.List;
import java.util.Optional;

/**
 * Aggregate persistence facade used by the desktop application.
 * <p>
 * Extends database-neutral repository contracts so the same application code can later
 * target PostgreSQL without rewriting domain/processing logic. Implementations must never
 * persist decoded waveform samples.
 */
public interface ProjectMetadataStore extends
        ProjectRepository,
        InventoryRepository,
        ProcessingRepository,
        ProcessingLedgerRepository,
        EngineeringBookmarkRepository,
        MapRouteStateRepository,
        SemanticSignalMappingRepository,
        GraphInvestigationRepository,
        GraphAnnotationRepository,
        AuditRepository,
        ExportJobRepository,
        com.stellar.platform.storage.api.AnalysisRepository,
        com.stellar.platform.storage.api.DiagnosticRepository,
        com.stellar.platform.storage.api.InvestigationRepository {

    // ProjectRepository / InventoryRepository / ProcessingRepository methods are inherited.

    /** @deprecated Prefer {@link #upsertProject(ProjectMetadata)} via {@link ProjectRepository}. */
    @Override
    void upsertProject(ProjectMetadata project);

    @Override
    void upsertFile(ProjectFileMetadata file);

    @Override
    void deleteFile(String projectId, String fileId);

    @Override
    void saveInventory(String projectId, String sourceId, CanSourceInventory inventory);

    @Override
    void upsertChannelMapping(ChannelMappingMetadata mapping);

    @Override
    void deleteChannelMapping(String projectId, String sourceId, int channelId);

    @Override
    void upsertProcessingRun(ProcessingRunMetadata run);

    @Override
    default void upsertProcessingArtifact(ProcessingArtifactMetadata artifact) {
    }

    @Override
    default void markProcessingArtifactsStatus(String projectId, String status) {
    }

    @Override
    default void deleteProcessingArtifacts(String projectId) {
    }

    @Override
    default List<ProjectMetadata> listProjects() {
        return List.of();
    }

    @Override
    default List<ProjectDashboardSummary> listProjectDashboardSummaries() {
        return List.of();
    }

    /** Saved-project count for status chrome. Does not load per-project aggregates. */
    default int countProjects() {
        return listProjects().size();
    }

    /**
     * Distinct catalog keys for one project. The SQLite store answers this with one
     * targeted count. Other stores may scan dashboard rows.
     */
    default int signalCatalogCount(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return 0;
        }
        return listProjectDashboardSummaries().stream()
                .filter(row -> projectId.equals(row.projectId()))
                .mapToInt(ProjectDashboardSummary::signalCount)
                .findFirst()
                .orElse(0);
    }

    /** Newest saved project by {@code updated_at}, without loading dashboard aggregates. */
    default java.util.Optional<String> latestUpdatedProjectId() {
        return listProjectDashboardSummaries().stream()
                .max(java.util.Comparator.comparing(
                        ProjectDashboardSummary::updatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(ProjectDashboardSummary::projectId);
    }

    @Override
    default Optional<SavedProjectSnapshot> loadProject(String projectId) {
        return Optional.empty();
    }

    /**
     * Runs {@code work} in one store transaction when the implementation supports it.
     * Default is a plain invocation.
     */
    default void runInTransaction(Runnable work) {
        if (work != null) {
            work.run();
        }
    }

    /** Lightweight Graphs analysis scope. Never stores samples or map objects. */
    default void upsertAnalysisScope(AnalysisScopeRecord record) {
    }

    default Optional<AnalysisScopeRecord> findAnalysisScope(String projectId) {
        return Optional.empty();
    }

    /** Inserts one rule version. Implementations must not overwrite an existing version. */
    default void insertEvaluationRule(EvaluationRuleRecord record) {
    }

    default List<EvaluationRuleRecord> listEvaluationRules(String projectId) {
        return List.of();
    }

    /** Inserts one frozen result. Implementations must not replace the document of an existing result. */
    default void insertEvaluationResult(EvaluationResultRecord record) {
    }

    default List<EvaluationResultRecord> listEvaluationResults(String projectId) {
        return List.of();
    }

    /** Changes report selection only. The stored result document stays as written. */
    default void setEvaluationIncluded(String projectId, String resultId, boolean included) {
    }
}
