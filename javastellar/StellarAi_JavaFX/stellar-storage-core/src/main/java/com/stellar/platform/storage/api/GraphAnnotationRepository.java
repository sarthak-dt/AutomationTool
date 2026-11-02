package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.GraphAnnotationMetadata;

import java.util.List;

/** User annotations anchored to signal timestamps. Never rendered geometry. */
public interface GraphAnnotationRepository {

    default void upsertGraphAnnotation(GraphAnnotationMetadata annotation) {
    }

    default void deleteGraphAnnotation(String projectId, String annotationId) {
    }

    /** Ordered by start timestamp so the graph can draw them in timeline order. */
    default List<GraphAnnotationMetadata> listGraphAnnotations(String projectId) {
        return List.of();
    }
}
