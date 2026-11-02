package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Cooperative cancellation boundary for disk-backed graph queries used during rapid zoom/pan. */
public interface CancellableLodGraphSeriesQuery extends LodGraphSeriesQuery {
    List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, BooleanSupplier continueQuery);

    List<GraphBucket> queryBuckets(
            SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets, BooleanSupplier continueQuery);
}
