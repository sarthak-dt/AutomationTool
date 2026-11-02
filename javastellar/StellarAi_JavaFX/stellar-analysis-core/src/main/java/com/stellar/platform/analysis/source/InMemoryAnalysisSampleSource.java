package com.stellar.platform.analysis.source;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test/fixture source. Production uses {@link AnalysisSampleSource#of} over a persistent store. */
public final class InMemoryAnalysisSampleSource implements AnalysisSampleSource {

    private final Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();

    public void put(SignalCatalogKey key, List<SignalSamplePoint> samples) {
        series.put(key, List.copyOf(samples == null ? List.of() : samples));
    }

    public void add(SignalCatalogKey key, long timestampNanos, double value) {
        add(key, timestampNanos, value, null, null);
    }

    public void add(SignalCatalogKey key, long timestampNanos, double value, Long raw, String text) {
        series.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new SignalSamplePoint(timestampNanos, value, raw, text));
    }

    @Override
    public int retainedSampleCount(SignalCatalogKey key) {
        List<SignalSamplePoint> list = series.get(key);
        return list == null ? 0 : list.size();
    }

    @Override
    public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        List<SignalSamplePoint> list = series.get(key);
        if (list == null || offset >= list.size() || limit <= 0) {
            return List.of();
        }
        int from = Math.max(0, offset);
        int to = Math.min(list.size(), from + limit);
        return List.copyOf(list.subList(from, to));
    }
}
