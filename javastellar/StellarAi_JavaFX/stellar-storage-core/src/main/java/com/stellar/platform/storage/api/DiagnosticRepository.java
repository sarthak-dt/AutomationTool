package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.DiagnosticBindingRow;
import com.stellar.platform.storage.metadata.DiagnosticFindingReviewRow;
import com.stellar.platform.storage.metadata.DiagnosticFindingRow;
import com.stellar.platform.storage.metadata.DiagnosticRulePackRow;
import com.stellar.platform.storage.metadata.DiagnosticRulePreferenceRow;
import com.stellar.platform.storage.metadata.DiagnosticRunMetadata;
import com.stellar.platform.storage.metadata.DiagnosticWorkspaceMetadata;

import java.util.List;
import java.util.Optional;

public interface DiagnosticRepository {

    default void upsertDiagnosticRulePack(DiagnosticRulePackRow pack) {
    }

    default Optional<DiagnosticRulePackRow> findDiagnosticRulePack(
            String projectId, String packId, String packVersion
    ) {
        return Optional.empty();
    }

    default List<DiagnosticRulePackRow> listDiagnosticRulePacks(String projectId) {
        return List.of();
    }

    default void upsertDiagnosticRun(DiagnosticRunMetadata run) {
    }

    default Optional<DiagnosticRunMetadata> findDiagnosticRun(String runId) {
        return Optional.empty();
    }

    default void replaceDiagnosticFindings(String runId, List<DiagnosticFindingRow> findings) {
    }

    default List<DiagnosticFindingRow> listDiagnosticFindings(String runId) {
        return List.of();
    }

    default void upsertDiagnosticBinding(DiagnosticBindingRow binding) {
    }

    default List<DiagnosticBindingRow> listDiagnosticBindings(String projectId, String packId) {
        return List.of();
    }

    default void upsertDiagnosticWorkspace(DiagnosticWorkspaceMetadata workspace) {
    }

    default Optional<DiagnosticWorkspaceMetadata> findDiagnosticWorkspace(String projectId) {
        return Optional.empty();
    }

    default int invalidateDiagnosticRuns(String projectId, long artifactGeneration) {
        return 0;
    }

    default List<DiagnosticRunMetadata> listDiagnosticRuns(String projectId) {
        return List.of();
    }

    default void deleteDiagnosticRulePack(String projectId, String packId, String packVersion) {
    }

    default void upsertDiagnosticFindingReview(DiagnosticFindingReviewRow review) {
    }

    default Optional<DiagnosticFindingReviewRow> findDiagnosticFindingReview(String findingId) {
        return Optional.empty();
    }

    default List<DiagnosticFindingReviewRow> listDiagnosticFindingReviews(String projectId) {
        return List.of();
    }

    default void upsertDiagnosticRulePreference(DiagnosticRulePreferenceRow preference) {
    }

    default List<DiagnosticRulePreferenceRow> listDiagnosticRulePreferences(String projectId, String packId) {
        return List.of();
    }
}
