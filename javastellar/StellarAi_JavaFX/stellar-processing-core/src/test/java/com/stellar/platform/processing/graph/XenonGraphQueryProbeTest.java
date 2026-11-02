package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.cache.GraphQueryCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Times the existing 73-file Xenon graph store. Skips when that isolated project is absent.
 * Does not modify artifacts.
 */
class XenonGraphQueryProbeTest {
    @Test
    void timeStoredQueriesOnIsolatedProject() throws Exception {
        Path storeRoot = Path.of(System.getenv("TEMP"),
                "stellar-accuracy", "m2-73-home", "analysis-storage", "StellarAIAnalysis",
                "projects", "1001", "signal-store");
        Assumptions.assumeTrue(Files.isDirectory(storeRoot), "isolated store missing: " + storeRoot);
        List<Path> members = Files.list(storeRoot)
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve("ch-0").resolve("_none_"))
                .filter(dir -> Files.isRegularFile(dir.resolve("series-index.v1")))
                .sorted()
                .toList();
        assertFalse(members.isEmpty());
        System.out.println("PROBE_MEMBERS=" + members.size());

        Path largest = null;
        long largestSamples = -1;
        SignalCatalogEntry largestEntry = null;
        long openNanos = 0;
        long catalogNanos = 0;
        for (Path member : members) {
            long t0 = System.nanoTime();
            try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(member)) {
                openNanos += System.nanoTime() - t0;
                long c0 = System.nanoTime();
                for (SignalCatalogEntry entry : store.catalogEntries()) {
                    if (entry.sampleCount() > largestSamples) {
                        largestSamples = entry.sampleCount();
                        largest = member;
                        largestEntry = entry;
                    }
                }
                catalogNanos += System.nanoTime() - c0;
            }
        }
        System.out.println("PROBE_OPEN_ALL_MS=" + (openNanos / 1_000_000L));
        System.out.println("PROBE_CATALOG_ALL_MS=" + (catalogNanos / 1_000_000L));
        System.out.println("PROBE_LARGEST_MEMBER=" + largest.getParent().getParent().getFileName());
        System.out.println("PROBE_LARGEST_SIGNAL=" + largestEntry.key().signalName()
                + " samples=" + largestEntry.sampleCount()
                + " first=" + largestEntry.firstTimestampNanos()
                + " last=" + largestEntry.lastTimestampNanos());

        long highResMs;
        try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(largest)) {
            SignalCatalogKey key = largestEntry.key();
            long start = largestEntry.firstTimestampNanos();
            long end = largestEntry.lastTimestampNanos();
            long span = Math.max(1L, end - start);
            timeBuckets(store, key, "full-budget16", start, end, 16);
            highResMs = timeBuckets(store, key, "full-budget1542", start, end, 1542);
            timeBuckets(store, key, "full-budget1542-warm", start, end, 1542);
            long narrowStart = start + span / 2;
            long narrowEnd = Math.min(end, narrowStart + 1_000_000L);
            timeBuckets(store, key, "narrow-1ms-budget1542", narrowStart, narrowEnd, 1542);
            timeBuckets(store, key, "narrow-1ms-warm", narrowStart, narrowEnd, 1542);
            long fractionStart = start + span / 3;
            long fractionEnd = fractionStart + Math.max(1L, span * 285 / 1000);
            timeBuckets(store, key, "fraction-28pct-budget1542", fractionStart, Math.min(end, fractionEnd), 1542);

            long raw0 = System.nanoTime();
            var raw = store.queryRange(key, narrowStart, narrowEnd);
            long rawMs = (System.nanoTime() - raw0) / 1_000_000L;
            System.out.println("PROBE_RAW_NARROW ms=" + rawMs + " points=" + raw.size()
                    + " thread=" + Thread.currentThread().getName());

            GraphViewportEngine engine = new GraphViewportEngine(store, 2, new GraphQueryCache(64));
            GraphViewportRequest wide = new GraphViewportRequest(List.of(key), start, end, 771, 0L);
            GraphViewportRequest deep = new GraphViewportRequest(List.of(key), narrowStart, narrowEnd, 771, 0L);
            timeEngine(engine, "engine-wide-cold", wide);
            timeEngine(engine, "engine-wide-warm", wide);
            timeEngine(engine, "engine-deep-cold", deep);
            timeEngine(engine, "engine-deep-warm", deep);
        }

        if (highResMs > 2_000L) {
            System.out.println("PROBE_FIT_SKIPPED highResMs=" + highResMs);
            return;
        }
        long fit0 = System.nanoTime();
        int fitBuckets = 0;
        int fitFiles = 0;
        for (Path member : members) {
            try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(member)) {
                SignalCatalogEntry speed = store.catalogEntries().stream()
                        .filter(entry -> entry.key().signalName().toLowerCase(Locale.ROOT).contains("engine_cycle_speed"))
                        .max(Comparator.comparingLong(SignalCatalogEntry::sampleCount))
                        .orElse(null);
                if (speed == null || speed.sampleCount() <= 0 || speed.firstTimestampNanos() == null) {
                    continue;
                }
                var buckets = store.queryBuckets(
                        speed.key(), speed.firstTimestampNanos(), speed.lastTimestampNanos(), 20);
                fitBuckets += buckets.size();
                fitFiles++;
            }
        }
        System.out.println("PROBE_FIT_ONE_SIGNAL_MS=" + ((System.nanoTime() - fit0) / 1_000_000L)
                + " files=" + fitFiles + " buckets=" + fitBuckets);
    }

    private static long timeBuckets(
            LosslessGraphSignalStore store, SignalCatalogKey key, String label,
            long start, long end, int budget) {
        long t0 = System.nanoTime();
        var buckets = store.queryBuckets(key, start, end, budget);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long samples = buckets.stream().mapToLong(GraphBucket::sampleCount).sum();
        long exact = buckets.stream()
                .filter(bucket -> bucket.sampleCount() == 1
                        && bucket.firstTimestampNanos() == bucket.lastTimestampNanos())
                .count();
        long maxCount = buckets.stream().mapToLong(GraphBucket::sampleCount).max().orElse(0);
            System.out.println("PROBE_" + label
                + " ms=" + ms
                + " buckets=" + buckets.size()
                + " samplesInBuckets=" + samples
                + " exactBuckets=" + exact
                + " maxBucketSamples=" + maxCount
                + " thread=" + Thread.currentThread().getName());
        return ms;
    }

    private static void timeEngine(GraphViewportEngine engine, String label, GraphViewportRequest request) {
        long t0 = System.nanoTime();
        var series = engine.render(request);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        GraphRenderSeries rendered = series.getFirst();
        long exact = rendered.buckets().stream()
                .filter(bucket -> bucket.sampleCount() == 1
                        && bucket.firstTimestampNanos() == bucket.lastTimestampNanos())
                .count();
        System.out.println("PROBE_" + label
                + " ms=" + ms
                + " fullResolution=" + rendered.fullResolution()
                + " sourceSamples=" + rendered.sourceSampleCount()
                + " buckets=" + rendered.buckets().size()
                + " exactBuckets=" + exact
                + " cacheHits=" + engine.queryCache().stats().hits()
                + " cacheMisses=" + engine.queryCache().stats().misses()
                + " cacheSize=" + engine.queryCache().stats().size());
    }
}
