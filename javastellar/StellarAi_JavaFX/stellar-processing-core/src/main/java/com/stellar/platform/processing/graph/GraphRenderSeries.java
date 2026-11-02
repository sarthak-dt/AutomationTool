package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import java.util.List;
import java.util.Objects;

/** Renderer-neutral result. Canvas/ECharts consume this rather than raw stores. */
public record GraphRenderSeries(
        SignalCatalogKey key,
        String unit,
        long sourceSampleCount,
        boolean fullResolution,
        long viewportStartTimestampNanos,
        long viewportEndTimestampNanos,
        List<GraphBucket> buckets
) {
    public GraphRenderSeries {
        Objects.requireNonNull(key, "key");
        unit = unit == null ? "" : unit;
        if (viewportEndTimestampNanos < viewportStartTimestampNanos) {
            throw new IllegalArgumentException("viewport end before start");
        }
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }
}
