package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

/** Exact persisted sample selected for an engineering cursor. */
public record GraphCursorSample(SignalCatalogKey key, long timestampNanos, double physicalValue,
                                Long rawValue, String textualValue, String unit) { }
