package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import java.util.List;

/** Optional disk-side LOD contract that prevents large viewport ranges from materializing every raw sample on heap. */
public interface LodGraphSeriesQuery extends GraphSeriesQuery {
    List<GraphBucket> queryBuckets(SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets);
}
