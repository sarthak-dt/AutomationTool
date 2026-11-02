package com.stellar.platform.api.local;

import com.stellar.platform.api.SignalQueryApi;
import com.stellar.platform.api.dto.SignalBucketDto;
import com.stellar.platform.api.dto.SignalCatalogEntryDto;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalFeatureDto;
import com.stellar.platform.api.dto.SignalSampleDto;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalStatsResultDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;
import com.stellar.platform.api.dto.SignalViewportSeriesDto;
import com.stellar.platform.api.support.PlatformMetrics;
import com.stellar.platform.api.support.ProcessSessionRegistry;
import com.stellar.platform.api.support.SignalKeyCodec;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.analysis.AutomotiveAnalysisEngine;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LocalSignalQueryApi implements SignalQueryApi {

    public static final int MAX_SAMPLE_PAGE = 5000;
    public static final int DEFAULT_SAMPLE_PAGE = 1000;

    private final ProcessSessionRegistry sessions;

    public LocalSignalQueryApi(ProcessSessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public SignalCatalogPageDto catalog(String projectId, SignalCatalogQuery query) {
        long started = System.nanoTime();
        Objects.requireNonNull(query, "query");
        SignalSeriesStore store = requireStore(projectId);
        SignalCatalog snapshot = store.snapshot();
        List<SignalCatalogEntry> filtered = filterCatalog(snapshot, query);
        int safeOffset = query.offset();
        int safeLimit = query.limit();
        List<SignalCatalogEntryDto> page = new ArrayList<>(Math.min(safeLimit, filtered.size()));
        for (int i = safeOffset; i < filtered.size() && page.size() < safeLimit; i++) {
            page.add(toEntryDto(filtered.get(i)));
        }
        PlatformMetrics.get().recordDurationMs(
                "query.latency", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return new SignalCatalogPageDto(
                projectId,
                query.query(),
                query.sourceType(),
                query.sourceFile(),
                safeOffset,
                safeLimit,
                filtered.size(),
                page
        );
    }

    @Override
    public SignalSamplePageDto samples(String projectId, String signalKey, int offset, int limit) {
        long started = System.nanoTime();
        SignalSeriesStore store = requireStore(projectId);
        SignalCatalogKey key = SignalKeyCodec.decode(signalKey);
        int safeOffset = Math.max(0, offset);
        int safeLimit = clampSampleLimit(limit);
        List<SignalSamplePoint> points = store.querySamples(key, safeOffset, safeLimit);
        int total = store.retainedSampleCount(key);
        PlatformMetrics.get().recordDurationMs(
                "query.latency", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return new SignalSamplePageDto(projectId, signalKey, safeOffset, safeLimit, total, toSampleDtos(points));
    }

    @Override
    public SignalSamplePageDto range(
            String projectId, String signalKey, long fromNanos, long toNanos, int limit) {
        long started = System.nanoTime();
        SignalSeriesStore store = requireStore(projectId);
        SignalCatalogKey key = SignalKeyCodec.decode(signalKey);
        int safeLimit = clampSampleLimit(limit);
        long from = Math.min(fromNanos, toNanos);
        long to = Math.max(fromNanos, toNanos);
        List<SignalSamplePoint> points;
        if (store instanceof GraphQueryProvider provider) {
            // Indexed segment skip + early-stop — never preload the entire retained series.
            points = provider.graphQuery().queryRange(key, from, to, safeLimit);
        } else {
            points = pagedRangeFilter(store, key, from, to, safeLimit);
        }
        PlatformMetrics.get().recordDurationMs(
                "query.latency", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return new SignalSamplePageDto(projectId, signalKey, 0, safeLimit, points.size(), toSampleDtos(points));
    }

    @Override
    public SignalViewportResultDto viewport(String projectId, SignalViewportRequestDto request) {
        long started = System.nanoTime();
        Objects.requireNonNull(request, "request");
        SignalSeriesStore store = requireStore(projectId);
        GraphSeriesQuery graphQuery = requireGraphQuery(store);
        List<SignalCatalogKey> keys = decodeKeys(request.signalKeys());
        GraphViewportEngine engine = new GraphViewportEngine(graphQuery);
        List<GraphRenderSeries> rendered = engine.render(new GraphViewportRequest(
                keys, request.fromNanos(), request.toNanos(), request.pixelWidth()));
        List<SignalViewportSeriesDto> series = new ArrayList<>(rendered.size());
        for (GraphRenderSeries s : rendered) {
            series.add(new SignalViewportSeriesDto(
                    SignalKeyCodec.encode(s.key()),
                    s.unit(),
                    s.sourceSampleCount(),
                    s.fullResolution(),
                    s.viewportStartTimestampNanos(),
                    s.viewportEndTimestampNanos(),
                    toBucketDtos(s.buckets())
            ));
        }
        PlatformMetrics.get().recordDurationMs(
                "query.latency", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return new SignalViewportResultDto(
                projectId, request.fromNanos(), request.toNanos(), request.pixelWidth(), series);
    }

    @Override
    public SignalStatsResultDto viewportStats(String projectId, SignalViewportRequestDto request) {
        SignalViewportResultDto viewport = viewport(projectId, request);
        List<GraphRenderSeries> series = new ArrayList<>(viewport.series().size());
        for (SignalViewportSeriesDto s : viewport.series()) {
            series.add(new GraphRenderSeries(
                    SignalKeyCodec.decode(s.signalKey()),
                    s.unit(),
                    s.sourceSampleCount(),
                    s.fullResolution(),
                    s.viewportStartNanos(),
                    s.viewportEndNanos(),
                    toBuckets(s.buckets())
            ));
        }
        List<SignalFeatureDto> features = new ArrayList<>();
        for (AutomotiveAnalysisEngine.SignalFeatureSet f : AutomotiveAnalysisEngine.extractFeatures(series)) {
            features.add(new SignalFeatureDto(
                    f.signal(), f.unit(), f.representedSamples(),
                    f.min(), f.max(), f.mean(), f.median(), f.variance(), f.standardDeviation(), f.rms(),
                    f.p05(), f.p95(), f.slopePerSecond(), f.driftPerMinute(), f.meanAbsRatePerSecond(),
                    f.peakCount(), f.valleyCount(), f.stepChangeCount(), f.outlierCount(),
                    f.jitter(), f.oscillationHz()
            ));
        }
        return new SignalStatsResultDto(
                projectId, request.fromNanos(), request.toNanos(), request.pixelWidth(), features);
    }

    /**
     * In-memory / non-graph stores: page samples and filter by time until the bound is filled.
     * Never calls {@code querySamples(0, retainedCount)}.
     */
    static List<SignalSamplePoint> pagedRangeFilter(
            SignalSeriesStore store,
            SignalCatalogKey key,
            long from,
            long to,
            int maxSamples
    ) {
        int limit = Math.max(1, maxSamples);
        List<SignalSamplePoint> out = new ArrayList<>(Math.min(limit, 1024));
        int pageSize = Math.min(limit, 1000);
        int offset = 0;
        while (out.size() < limit) {
            List<SignalSamplePoint> page = store.querySamples(key, offset, pageSize);
            if (page.isEmpty()) {
                break;
            }
            boolean pastEnd = false;
            for (SignalSamplePoint p : page) {
                long ts = p.timestampNanos();
                if (ts > to) {
                    pastEnd = true;
                    break;
                }
                if (ts >= from) {
                    out.add(p);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            offset += page.size();
            if (pastEnd || page.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<SignalCatalogEntry> filterCatalog(SignalCatalog snapshot, SignalCatalogQuery query) {
        String q = query.query().trim().toLowerCase(Locale.ROOT);
        SignalSourceType typeFilter = parseSourceType(query.sourceType());
        String sourceFile = query.sourceFile().toLowerCase(Locale.ROOT);
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry e : snapshot.entries()) {
            if (typeFilter != null && e.sourceType() != typeFilter) {
                continue;
            }
            if (!sourceFile.isEmpty()) {
                String file = e.sourceFile().isBlank() ? e.key().sourceFile() : e.sourceFile();
                if (!file.toLowerCase(Locale.ROOT).contains(sourceFile)) {
                    continue;
                }
            }
            if (!q.isEmpty() && !e.key().searchBlob().contains(q)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static SignalSourceType parseSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "DBC", "DBC_CAN" -> SignalSourceType.DBC_CAN;
            case "NATIVE", "MF4_NATIVE" -> SignalSourceType.MF4_NATIVE;
            case "COMPUTED", "COMPUTED_FUTURE" -> SignalSourceType.COMPUTED_FUTURE;
            default -> {
                try {
                    yield SignalSourceType.valueOf(v);
                } catch (RuntimeException ex) {
                    yield null;
                }
            }
        };
    }

    private static SignalCatalogEntryDto toEntryDto(SignalCatalogEntry entry) {
        return new SignalCatalogEntryDto(
                SignalKeyCodec.encode(entry.key()),
                entry.signalName(),
                entry.unit(),
                entry.messageName(),
                entry.sourceType().name(),
                entry.canId(),
                entry.channel(),
                entry.sourceFile().isBlank() ? entry.key().sourceFile() : entry.sourceFile(),
                entry.sourceDatabase().isBlank() ? entry.key().sourceDatabase() : entry.sourceDatabase(),
                entry.sampleCount(),
                entry.firstTimestampNanos(),
                entry.lastTimestampNanos(),
                entry.minPhysical(),
                entry.maxPhysical()
        );
    }

    private static List<SignalSampleDto> toSampleDtos(List<SignalSamplePoint> points) {
        List<SignalSampleDto> samples = new ArrayList<>(points.size());
        for (SignalSamplePoint p : points) {
            samples.add(new SignalSampleDto(
                    p.timestampNanos(), p.physicalValue(), p.rawValue(), p.textualValue()));
        }
        return samples;
    }

    private static List<SignalBucketDto> toBucketDtos(List<GraphBucket> buckets) {
        List<SignalBucketDto> out = new ArrayList<>(buckets.size());
        for (GraphBucket b : buckets) {
            out.add(new SignalBucketDto(
                    b.firstTimestampNanos(), b.firstValue(),
                    b.minTimestampNanos(), b.minValue(),
                    b.maxTimestampNanos(), b.maxValue(),
                    b.lastTimestampNanos(), b.lastValue(),
                    b.sampleCount()
            ));
        }
        return out;
    }

    private static List<GraphBucket> toBuckets(List<SignalBucketDto> buckets) {
        List<GraphBucket> out = new ArrayList<>(buckets.size());
        for (SignalBucketDto b : buckets) {
            out.add(new GraphBucket(
                    b.firstTimestampNanos(), b.firstValue(),
                    b.minTimestampNanos(), b.minValue(),
                    b.maxTimestampNanos(), b.maxValue(),
                    b.lastTimestampNanos(), b.lastValue(),
                    b.sampleCount()
            ));
        }
        return out;
    }

    private static List<SignalCatalogKey> decodeKeys(List<String> encoded) {
        List<SignalCatalogKey> keys = new ArrayList<>(encoded.size());
        for (String key : encoded) {
            if (key == null || key.isBlank()) {
                continue;
            }
            keys.add(SignalKeyCodec.decode(key));
        }
        return keys;
    }

    private static GraphSeriesQuery requireGraphQuery(SignalSeriesStore store) {
        if (store instanceof GraphQueryProvider provider) {
            return provider.graphQuery();
        }
        throw new IllegalStateException(
                "Viewport/LOD queries require a graph-capable signal store (lossless session)");
    }

    private static int clampSampleLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? DEFAULT_SAMPLE_PAGE : limit, MAX_SAMPLE_PAGE));
    }

    private SignalSeriesStore requireStore(String projectId) {
        return sessions.get(projectId)
                .orElseThrow(() -> new IllegalStateException(
                        "No processed signal session for project " + projectId
                                + "; run a process job first"));
    }
}
