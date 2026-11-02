package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.ExportJobMetadata;

import java.util.List;
import java.util.Optional;

/** Export Center job status and history. */
public interface ExportJobRepository {

    default void upsertExportJob(ExportJobMetadata job) {
    }

    default Optional<ExportJobMetadata> findExportJob(String exportId) {
        return Optional.empty();
    }

    /** Most recent first, bounded by {@code limit}. */
    default List<ExportJobMetadata> listExportJobs(String projectId, int limit) {
        return List.of();
    }
}
