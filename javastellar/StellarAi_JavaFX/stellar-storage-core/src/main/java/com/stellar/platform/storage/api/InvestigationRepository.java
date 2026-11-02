package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.EngineeringInvestigationRow;
import com.stellar.platform.storage.metadata.InvestigationEvidenceRow;
import com.stellar.platform.storage.metadata.InvestigationObservationRow;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import com.stellar.platform.storage.metadata.InvestigationWorkspaceMetadata;

import java.util.List;
import java.util.Optional;

/** Engineering investigation metadata. Never stores waveform samples. */
public interface InvestigationRepository {

    default void upsertEngineeringInvestigation(EngineeringInvestigationRow row) {
    }

    default void replaceInvestigationSignals(String investigationId, List<String> signalKeyBlobs) {
    }

    default List<String> listInvestigationSignals(String investigationId) {
        return List.of();
    }

    default void replaceInvestigationEvidence(String investigationId, List<InvestigationEvidenceRow> evidence) {
    }

    default List<InvestigationEvidenceRow> listInvestigationEvidence(String investigationId) {
        return List.of();
    }

    default void replaceInvestigationObservations(
            String investigationId, List<InvestigationObservationRow> observations
    ) {
    }

    default List<InvestigationObservationRow> listInvestigationObservations(String investigationId) {
        return List.of();
    }

    default Optional<EngineeringInvestigationRow> findEngineeringInvestigation(String investigationId) {
        return Optional.empty();
    }

    default List<EngineeringInvestigationRow> listEngineeringInvestigations(String projectId) {
        return List.of();
    }

    default void deleteEngineeringInvestigation(String investigationId) {
    }

    default void upsertInvestigationReport(InvestigationReportRow row) {
    }

    default List<InvestigationReportRow> listInvestigationReports(String investigationId) {
        return List.of();
    }

    default List<InvestigationReportRow> listProjectReports(String projectId) {
        return List.of();
    }

    default void deleteInvestigationReport(String reportId) {
    }

    default Optional<InvestigationReportRow> findInvestigationReport(String reportId) {
        return Optional.empty();
    }

    default void upsertInvestigationWorkspace(InvestigationWorkspaceMetadata workspace) {
    }

    default Optional<InvestigationWorkspaceMetadata> findInvestigationWorkspace(String projectId) {
        return Optional.empty();
    }

    default int countEngineeringInvestigations(String projectId) {
        return 0;
    }
}
