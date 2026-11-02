package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.cache.GraphQueryCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds bounded renderer-neutral frames for the current viewport. */
public final class GraphViewportEngine {
    private static final int DEFAULT_PRIMITIVES_PER_PIXEL = 2;
    private final GraphSeriesQuery query;
    private final int primitivesPerPixel;
    private final GraphQueryCache queryCache;
    private volatile long artifactGeneration;

    public GraphViewportEngine(GraphSeriesQuery query) {
        this(query, DEFAULT_PRIMITIVES_PER_PIXEL, new GraphQueryCache(64));
    }

    public GraphViewportEngine(GraphSeriesQuery query, int primitivesPerPixel) {
        this(query, primitivesPerPixel, new GraphQueryCache(64));
    }

    public GraphViewportEngine(GraphSeriesQuery query, int primitivesPerPixel, GraphQueryCache queryCache) {
        this.query = Objects.requireNonNull(query, "query");
        if (primitivesPerPixel < 1) throw new IllegalArgumentException("primitivesPerPixel must be >= 1");
        this.primitivesPerPixel = primitivesPerPixel;
        this.queryCache = queryCache == null ? new GraphQueryCache(64) : queryCache;
    }

    /** Call after reprocessing so cached LOD tiles from the previous artifact generation are unused. */
    public void setArtifactGeneration(long generation) {
        this.artifactGeneration = generation;
        queryCache.invalidateGeneration(generation);
    }

    public GraphQueryCache queryCache() {
        return queryCache;
    }

    public List<GraphRenderSeries> render(GraphViewportRequest request) {
        return render(request, () -> true);
    }

    /** Renders while the supplied token remains true. Used to abandon obsolete zoom/pan work. */
    public List<GraphRenderSeries> render(GraphViewportRequest request, BooleanSupplier continueQuery) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(continueQuery, "continueQuery");
        int budget = Math.max(1, request.pixelWidth() * primitivesPerPixel);
        List<GraphRenderSeries> out = new ArrayList<>(request.signalKeys().size());
        for (SignalCatalogKey key : request.signalKeys()) {
            checkCancelled(continueQuery);
            List<GraphBucket> buckets;
            boolean full;
            long totalSamples = query.sampleCount(key);
            if (query instanceof CancellableLodGraphSeriesQuery cancellable && totalSamples > budget) {
                GraphQueryCache.Key cacheKey = new GraphQueryCache.Key(
                        artifactGeneration, key, request.startTimestampNanos(), request.endTimestampNanos(), budget,
                        request.timeOriginNanos());
                java.util.Optional<List<GraphBucket>> cached = queryCache.get(cacheKey);
                if (cached.isPresent()) {
                    buckets = cached.get();
                } else {
                    buckets = cancellable.queryBuckets(
                            key, request.startTimestampNanos(), request.endTimestampNanos(), budget, continueQuery);
                    checkCancelled(continueQuery);
                    queryCache.put(cacheKey, buckets);
                }
                buckets = exactWindow(cancellable, key, request, budget, buckets, continueQuery, cacheKey);
                full = allExact(buckets);
            } else if (query instanceof LodGraphSeriesQuery lodQuery && totalSamples > budget) {
                GraphQueryCache.Key cacheKey = new GraphQueryCache.Key(
                        artifactGeneration, key, request.startTimestampNanos(), request.endTimestampNanos(), budget,
                        request.timeOriginNanos());
                buckets = queryCache.computeIfAbsent(cacheKey, ignored ->
                        lodQuery.queryBuckets(key, request.startTimestampNanos(), request.endTimestampNanos(), budget));
                full = allExact(buckets);
            } else {
                List<SignalSamplePoint> raw = query instanceof CancellableLodGraphSeriesQuery cancellable
                        ? cancellable.queryRange(key, request.startTimestampNanos(), request.endTimestampNanos(), continueQuery)
                        : query.queryRange(key, request.startTimestampNanos(), request.endTimestampNanos());
                checkCancelled(continueQuery);
                full = raw.size() <= budget;
                buckets = MinMaxLodReducer.reduce(raw, budget);
            }
            out.add(new GraphRenderSeries(
                    key, query.unit(key), totalSamples, full,
                    request.startTimestampNanos(), request.endTimestampNanos(), buckets));
        }
        return List.copyOf(out);
    }

    /**
     * LOD buckets are aggregates. When the window itself holds no more samples than the
     * pixel budget, replace them with one bucket per stored sample. No timestamps are invented.
     */
    private List<GraphBucket> exactWindow(
            CancellableLodGraphSeriesQuery query,
            SignalCatalogKey key,
            GraphViewportRequest request,
            int budget,
            List<GraphBucket> buckets,
            BooleanSupplier continueQuery,
            GraphQueryCache.Key cacheKey
    ) {
        long covered = 0L;
        for (GraphBucket bucket : buckets) {
            covered += bucket.sampleCount();
        }
        if (covered <= 0 || covered > budget || allExact(buckets)) {
            return buckets;
        }
        List<SignalSamplePoint> raw = query.queryRange(
                key, request.startTimestampNanos(), request.endTimestampNanos(), continueQuery);
        checkCancelled(continueQuery);
        if (raw.size() != covered || raw.size() > budget) {
            return buckets;
        }
        List<GraphBucket> exact = MinMaxLodReducer.reduce(raw, Math.max(budget, raw.size()));
        queryCache.put(cacheKey, exact);
        return exact;
    }

    private static boolean allExact(List<GraphBucket> buckets) {
        if (buckets.isEmpty()) {
            return false;
        }
        for (GraphBucket bucket : buckets) {
            if (bucket.sampleCount() != 1 || bucket.firstTimestampNanos() != bucket.lastTimestampNanos()) {
                return false;
            }
        }
        return true;
    }

    private static void checkCancelled(BooleanSupplier continueQuery) {
        if (!continueQuery.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Obsolete graph viewport query");
        }
    }
}
