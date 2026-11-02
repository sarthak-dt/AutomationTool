package com.stellar.platform.processing.graph;

/** Implemented by signal stores that can supply the full-resolution graph query path. */
public interface GraphQueryProvider {
    GraphSeriesQuery graphQuery();
}
