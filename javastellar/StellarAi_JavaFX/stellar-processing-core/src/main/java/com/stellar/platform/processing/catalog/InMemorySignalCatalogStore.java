package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Streaming builder for a dynamic {@link SignalCatalog} plus an in-memory series index.
 * <p>
 * Catalog metadata scales with unique signal definitions. Sample storage is bounded per series
 * (ring) so multi-GB files do not retain every value forever. The query API is intentionally
 * pageable so a future SQLite backend can replace this store without UI changes.
 */
public final class InMemorySignalCatalogStore implements SignalSeriesStore {

    public static final int DEFAULT_MAX_SAMPLES_PER_SIGNAL = 50_000;
    public static final int DEFAULT_QUERY_PAGE_LIMIT = 5_000;

    /** MF4 native measurement message tag used by the MF4 native resolver. */
    public static final String MF4_MEASUREMENT_MESSAGE = "MF4_MEASUREMENT";

    /**
     * Provenance tag for samples produced by direct DAT/LOG ingestion.
     * Catalog identity still includes the source file name, so these signals do not
     * collide with DBC or MF4-native signals from another measurement.
     */
    public static final String DIRECT_MEASUREMENT_MESSAGE = "DIRECT_MEASUREMENT";

    private final int maxSamplesPerSignal;
    private final String measurementSource;
    private final String definitionSource;
    private final Map<SignalCatalogKey, MutableSeries> seriesByKey = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public InMemorySignalCatalogStore(String measurementSource, String definitionSource) {
        this(measurementSource, definitionSource, DEFAULT_MAX_SAMPLES_PER_SIGNAL);
    }

    public InMemorySignalCatalogStore(
            String measurementSource,
            String definitionSource,
            int maxSamplesPerSignal
    ) {
        if (maxSamplesPerSignal < 1) {
            throw new IllegalArgumentException("maxSamplesPerSignal must be >= 1");
        }
        this.measurementSource = measurementSource == null ? "" : measurementSource;
        this.definitionSource = definitionSource == null ? "" : definitionSource;
        this.maxSamplesPerSignal = maxSamplesPerSignal;
    }

    public void accept(List<CanonicalSignal> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            for (CanonicalSignal signal : batch) {
                SignalCatalogKey key = keyOf(signal, measurementSource, definitionSource);
                MutableSeries series = seriesByKey.computeIfAbsent(key, k -> new MutableSeries(
                        signal.unit(),
                        maxSamplesPerSignal,
                        measurementSource,
                        definitionSource
                ));
                series.accept(signal);
            }
        } finally {
            lock.unlock();
        }
    }

    public SignalCatalog snapshot() {
        lock.lock();
        try {
            List<SignalCatalogEntry> entries = new ArrayList<>(seriesByKey.size());
            for (Map.Entry<SignalCatalogKey, MutableSeries> e : seriesByKey.entrySet()) {
                entries.add(e.getValue().toEntry(e.getKey()));
            }
            return new SignalCatalog(entries, measurementSource, definitionSource).sortedByName();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Seeds catalog metadata without loading sample payloads. Used when reopening a
     * project-scoped lossless store — samples continue to be served from disk.
     */
    public void seedCatalogMetadata(List<SignalCatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        lock.lock();
        try {
            for (SignalCatalogEntry entry : entries) {
                if (entry == null) continue;
                seriesByKey.computeIfAbsent(entry.key(), key -> MutableSeries.fromEntry(entry, maxSamplesPerSignal));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a bounded page of samples for the selected catalog key.
     *
     * @param offset zero-based sample index into the retained window
     * @param limit  max rows to return (capped)
     */
    public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        Objects.requireNonNull(key, "key");
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(1, limit), DEFAULT_QUERY_PAGE_LIMIT);
        lock.lock();
        try {
            MutableSeries series = seriesByKey.get(key);
            if (series == null) {
                return List.of();
            }
            return series.page(safeOffset, safeLimit);
        } finally {
            lock.unlock();
        }
    }

    public int retainedSampleCount(SignalCatalogKey key) {
        lock.lock();
        try {
            MutableSeries series = seriesByKey.get(key);
            return series == null ? 0 : series.retainedSize();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Zero-based global index of the first sample still retained in the bounded window.
     * When the series is not truncated this is {@code 0}; otherwise early samples were
     * evicted and cannot be queried.
     */
    public long retainedWindowStartIndex(SignalCatalogKey key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            MutableSeries series = seriesByKey.get(key);
            if (series == null) {
                return 0L;
            }
            long retained = series.retainedSize();
            long total = series.sampleCount;
            return Math.max(0L, total - retained);
        } finally {
            lock.unlock();
        }
    }

    public static SignalCatalogKey keyOf(
            CanonicalSignal signal,
            String measurementSource,
            String definitionSource
    ) {
        Objects.requireNonNull(signal, "signal");
        return new SignalCatalogKey(
                signal.signalName(),
                sourceTypeOf(signal),
                signal.messageName(),
                signal.canId(),
                signal.channel(),
                measurementSource == null ? "" : measurementSource,
                definitionSource == null ? "" : definitionSource
        );
    }

    public static SignalCatalogKey keyOf(CanonicalSignal signal) {
        return keyOf(signal, "", "");
    }

    public static SignalSourceType sourceTypeOf(CanonicalSignal signal) {
        if (MF4_MEASUREMENT_MESSAGE.equals(signal.messageName())
                || DIRECT_MEASUREMENT_MESSAGE.equals(signal.messageName())) {
            return SignalSourceType.MF4_NATIVE;
        }
        return SignalSourceType.DBC_CAN;
    }

    private static final class MutableSeries {
        private final String unit;
        private final int capacity;
        private final String sourceFile;
        private final String sourceDatabase;
        /** O(1) eviction ring — equal physical/raw values at distinct timestamps are all retained. */
        private final ArrayDeque<SignalSamplePoint> window;
        private long sampleCount;
        private Long firstTs;
        private Long lastTs;
        private Double minPhys;
        private Double maxPhys;
        private boolean truncated;

        MutableSeries(String unit, int capacity, String sourceFile, String sourceDatabase) {
            this.unit = unit == null ? "" : unit;
            this.capacity = capacity;
            this.sourceFile = sourceFile;
            this.sourceDatabase = sourceDatabase;
            this.window = new ArrayDeque<>(Math.min(capacity, 1024));
        }

        static MutableSeries fromEntry(SignalCatalogEntry entry, int capacity) {
            MutableSeries series = new MutableSeries(
                    entry.unit(), capacity, entry.sourceFile(), entry.sourceDatabase());
            series.sampleCount = entry.sampleCount();
            series.firstTs = entry.firstTimestampNanos();
            series.lastTs = entry.lastTimestampNanos();
            series.minPhys = entry.minPhysical();
            series.maxPhys = entry.maxPhysical();
            return series;
        }

        void accept(CanonicalSignal s) {
            sampleCount++;
            long ts = s.timestampNanos();
            if (firstTs == null) {
                firstTs = ts;
            }
            lastTs = ts;
            if (s.usableMeasurement()) {
                double p = s.physicalValue();
                if (minPhys == null || p < minPhys) {
                    minPhys = p;
                }
                if (maxPhys == null || p > maxPhys) {
                    maxPhys = p;
                }
            }
            if (window.size() >= capacity) {
                window.removeFirst();
                truncated = true;
            }
            window.addLast(new SignalSamplePoint(
                    ts, s.physicalValue(), s.rawValue(), s.textualValue(), s.validity()));
        }

        SignalCatalogEntry toEntry(SignalCatalogKey key) {
            return new SignalCatalogEntry(
                    key,
                    unit,
                    sampleCount,
                    firstTs,
                    lastTs,
                    minPhys,
                    maxPhys,
                    sourceFile,
                    sourceDatabase
            );
        }

        int retainedSize() {
            return window.size();
        }

        List<SignalSamplePoint> page(int offset, int limit) {
            if (offset >= window.size()) {
                return List.of();
            }
            int end = Math.min(window.size(), offset + limit);
            List<SignalSamplePoint> out = new ArrayList<>(end - offset);
            int i = 0;
            for (SignalSamplePoint point : window) {
                if (i >= end) {
                    break;
                }
                if (i >= offset) {
                    out.add(point);
                }
                i++;
            }
            return List.copyOf(out);
        }
    }
}
