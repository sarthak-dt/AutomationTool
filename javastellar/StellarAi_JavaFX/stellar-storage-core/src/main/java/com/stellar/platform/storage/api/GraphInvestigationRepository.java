package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.GraphInvestigationMetadata;

import java.util.Optional;

/** Logical graph investigation state — no waveform samples. */
public interface GraphInvestigationRepository {
    default void upsertGraphInvestigation(GraphInvestigationMetadata state) {
    }

    default Optional<GraphInvestigationMetadata> findGraphInvestigation(String projectId) {
        return Optional.empty();
    }

    default void deleteGraphInvestigation(String projectId) {
    }
}
