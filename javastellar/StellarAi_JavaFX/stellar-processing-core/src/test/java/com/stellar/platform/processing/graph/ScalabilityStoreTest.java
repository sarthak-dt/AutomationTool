package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.cache.GraphQueryCache;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic decoded workloads for Milestone 4.2.
 * These are generated series, not copies of the Xenon recordings and not 300 real files.
 * Each recording has its own measurement identity. Timestamps are the values written here.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ScalabilityStoreTest {

    static final int LARGE_SAMPLES = 2_000_000;
    static final int RECORDINGS = 300;
    static final int SAMPLES_PER_RECORDING = 200;
    static final int GPS_SAMPLES = 20;
    static final int LOGICAL_SIGNALS = 120;
    static final int SAMPLES_PER_SIGNAL = 500;
    static final long STEP_NANOS = 1_000L;

    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");

    @TempDir
    Path temp;

    private final StringBuilder notes = new StringBuilder();

    @Test
    void syntheticWorkloadsKeepEverySampleAndTimeTheLinearScan() throws Exception {
        try {
            note("source=synthetic");
            note("methodology=generated monotonic timestamps and values; distinct measurement ids "
                    + "synthetic-m42-000..299; no customer file was read or copied");
            note("layout=series-index.v1 validity layout; no format migration");
            measureLargeRecording();
            measureManySignals();
            measureManyRecordings();
        } finally {
            Files.createDirectories(EVIDENCE);
            Files.writeString(EVIDENCE.resolve("m42-store-notes.txt"), notes.toString());
        }
    }

    private void measureLargeRecording() throws Exception {
        Path root = temp.resolve("large");
        String measurement = "synthetic-m42-large";
        long writeStarted = System.nanoTime();
        SignalCatalogKey speed;
        SignalCatalogKey status;
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(
                root, measurement, "", LosslessGraphSignalStore.DEFAULT_SAMPLES_PER_SEGMENT)) {
            List<CanonicalSignal> batch = new ArrayList<>(4_000);
            for (int i = 0; i < LARGE_SAMPLES; i++) {
                batch.add(numeric("SYN_Speed", 0x180, i * STEP_NANOS, speedValue(i), (long) i));
                if (batch.size() == 4_000) {
                    store.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                store.accept(batch);
            }
            store.accept(List.of(
                    signal("SYN_Status", 0x181, 0L, 1.0, 1L, null, SignalValidity.VALID),
                    signal("SYN_Status", 0x181, STEP_NANOS, 42.5, 99L, null, SignalValidity.INVALID_RAW_CODE),
                    signal("SYN_Status", 0x181, 2 * STEP_NANOS, Double.NaN, null, "FALSE", SignalValidity.VALID)
            ));
            speed = InMemorySignalCatalogStore.keyOf(numeric("SYN_Speed", 0x180, 0L, 0, 0L), measurement, "");
            status = InMemorySignalCatalogStore.keyOf(
                    signal("SYN_Status", 0x181, 0L, 1.0, 1L, null, SignalValidity.VALID), measurement, "");
            assertEquals(LARGE_SAMPLES, store.sampleCount(speed));
            assertEquals(measurement, speed.sourceFile());
        }
        long writeMs = millis(writeStarted);
        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY, LosslessGraphSignalStore.indexLayoutVersion(root));
        int segmentFiles = countSegmentFiles(root);
        note("largeRecording source=synthetic measurement=" + measurement
                + " samples=" + LARGE_SAMPLES
                + " signals=2 segmentFiles=" + segmentFiles
                + " samplesPerSegment=" + LosslessGraphSignalStore.DEFAULT_SAMPLES_PER_SEGMENT
                + " writeMs=" + writeMs);

        long lastTimestamp = (LARGE_SAMPLES - 1L) * STEP_NANOS;
        try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(root)) {
            long coldStarted = System.nanoTime();
            List<GraphBucket> overview = store.queryBuckets(speed, 0L, lastTimestamp, 400);
            long coldMs = millis(coldStarted);
            long covered = overview.stream().mapToLong(GraphBucket::sampleCount).sum();
            assertEquals(LARGE_SAMPLES, covered);
            assertTrue(overview.stream().mapToDouble(GraphBucket::maxValue).max().orElseThrow() >= 1_000_000.0);

            long warmStarted = System.nanoTime();
            List<GraphBucket> second = store.queryBuckets(speed, 0L, lastTimestamp, 400);
            long warmMs = millis(warmStarted);
            assertEquals(covered, second.stream().mapToLong(GraphBucket::sampleCount).sum());

            long narrowStart = (LARGE_SAMPLES - 100L) * STEP_NANOS;
            long narrowStarted = System.nanoTime();
            List<SignalSamplePoint> narrow = store.queryRange(speed, narrowStart, lastTimestamp);
            long narrowMs = millis(narrowStarted);
            assertEquals(100, narrow.size());
            assertEquals(narrowStart, narrow.getFirst().timestampNanos());
            assertEquals(lastTimestamp, narrow.getLast().timestampNanos());
            for (int i = 0; i < narrow.size(); i++) {
                int index = LARGE_SAMPLES - 100 + i;
                SignalSamplePoint point = narrow.get(i);
                assertEquals(index * STEP_NANOS, point.timestampNanos());
                assertEquals(speedValue(index), point.physicalValue(), 0.0);
                assertEquals(SignalValidity.VALID, point.validity());
                if (i > 0) {
                    assertTrue(point.timestampNanos() > narrow.get(i - 1).timestampNanos());
                }
            }

            long earlyStarted = System.nanoTime();
            List<SignalSamplePoint> early = store.queryRange(speed, 10 * STEP_NANOS, 19 * STEP_NANOS);
            long earlyMs = millis(earlyStarted);
            assertEquals(10, early.size());
            assertEquals(10 * STEP_NANOS, early.getFirst().timestampNanos());

            long exactStarted = System.nanoTime();
            long spikeTimestamp = 1_000_000L * STEP_NANOS;
            List<SignalSamplePoint> spike = store.queryRange(speed, spikeTimestamp, spikeTimestamp);
            long exactMs = millis(exactStarted);
            assertEquals(1, spike.size());
            assertEquals(1_000_000.0, spike.getFirst().physicalValue(), 0.0);
            assertEquals(spikeTimestamp, spike.getFirst().timestampNanos());
            assertEquals(SignalValidity.VALID, spike.getFirst().validity());

            GraphViewportEngine engine = new GraphViewportEngine(store);
            List<GraphRenderSeries> deep = engine.render(new GraphViewportRequest(
                    List.of(speed), 10 * STEP_NANOS, 19 * STEP_NANOS, 640));
            assertEquals(1, deep.size());
            assertEquals(LARGE_SAMPLES, deep.getFirst().sourceSampleCount());
            assertTrue(deep.getFirst().fullResolution(), "deep zoom must keep one bucket per stored sample");
            assertEquals(10, deep.getFirst().buckets().size());
            for (int i = 0; i < 10; i++) {
                GraphBucket bucket = deep.getFirst().buckets().get(i);
                assertEquals(1, bucket.sampleCount());
                assertEquals((10 + i) * STEP_NANOS, bucket.firstTimestampNanos());
                assertEquals(speedValue(10 + i), bucket.firstValue(), 0.0);
            }

            assertTrue(store.queryRange(speed, -20L, -10L).isEmpty());
            assertTrue(store.queryRange(speed, lastTimestamp + 1_000L, lastTimestamp + 2_000L).isEmpty());

            List<SignalSamplePoint> statusPoints = store.queryRange(status, 0L, 2 * STEP_NANOS);
            assertEquals(3, statusPoints.size());
            assertEquals(SignalValidity.VALID, statusPoints.get(0).validity());
            assertEquals(1.0, statusPoints.get(0).physicalValue(), 0.0);
            assertEquals(SignalValidity.INVALID_RAW_CODE, statusPoints.get(1).validity());
            assertEquals(42.5, statusPoints.get(1).physicalValue(), 0.0);
            assertEquals(99L, statusPoints.get(1).rawValue());
            assertEquals(SignalValidity.VALID, statusPoints.get(2).validity());
            assertEquals("FALSE", statusPoints.get(2).textualValue());
            assertFalse(statusPoints.get(2).usableMeasurement());

            GraphQueryCache cache = new GraphQueryCache(1024);
            GraphQueryCache.Key cacheKey = new GraphQueryCache.Key(1L, speed, 0L, lastTimestamp, 400, 0L);
            long missStarted = System.nanoTime();
            cache.computeIfAbsent(cacheKey, ignored -> store.queryBuckets(speed, 0L, lastTimestamp, 400));
            long missMs = millis(missStarted);
            long hitStarted = System.nanoTime();
            List<GraphBucket> hit = cache.computeIfAbsent(cacheKey, ignored -> {
                throw new AssertionError("warm cache must not scan again");
            });
            long hitMs = millis(hitStarted);
            assertEquals(LARGE_SAMPLES, hit.stream().mapToLong(GraphBucket::sampleCount).sum());
            var stats = cache.stats();
            assertEquals(1, stats.hits());
            assertEquals(1, stats.misses());
            assertEquals(0, stats.evictions());

            GraphQueryCache tiny = new GraphQueryCache(4);
            for (int i = 0; i < 6; i++) {
                long end = STEP_NANOS * (i + 1);
                tiny.put(new GraphQueryCache.Key(1L, speed, 0L, end, 400, 0L), List.of());
            }
            assertTrue(tiny.stats().evictions() > 0, "bounded cache must evict: " + tiny.stats());
            assertTrue(tiny.stats().size() <= 4);

            AtomicInteger probes = new AtomicInteger();
            assertThrows(CancellationException.class, () -> store.queryBuckets(
                    speed, 0L, lastTimestamp, 400, () -> probes.incrementAndGet() < 3));
            assertTrue(probes.get() >= 3);

            long heapBeforeGc = usedHeap();
            System.gc();
            long heapAfterGc = usedHeap();
            note("largeQuery coldLodMs=" + coldMs
                    + " repeatLodMs=" + warmMs
                    + " narrow100Ms=" + narrowMs
                    + " early10Ms=" + earlyMs
                    + " exactSampleMs=" + exactMs
                    + " cacheMissMs=" + missMs
                    + " cacheHitMs=" + hitMs
                    + " cache=" + stats
                    + " tinyCache=" + tiny.stats()
                    + " heapBeforeGcMb=" + mb(heapBeforeGc)
                    + " heapAfterGcMb=" + mb(heapAfterGc));
            note("indexAssessment=no timestamp index was added. Full-range LOD reads every sample in range. "
                    + "A window inside one segment still scans that segment from its first record. "
                    + "Segment min/max timestamps skip the other segment files. "
                    + "coldLodMs=" + coldMs + " narrow100Ms=" + narrowMs + " exactSampleMs=" + exactMs);
        }
    }

    private void measureManySignals() throws Exception {
        Path root = temp.resolve("signals");
        String measurement = "synthetic-m42-signals";
        List<SignalCatalogKey> keys = new ArrayList<>();
        long writeStarted = System.nanoTime();
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, measurement, "", 4_096)) {
            for (int signal = 0; signal < LOGICAL_SIGNALS; signal++) {
                List<CanonicalSignal> batch = new ArrayList<>(SAMPLES_PER_SIGNAL);
                for (int i = 0; i < SAMPLES_PER_SIGNAL; i++) {
                    batch.add(numeric("SYN_Signal_" + signal, 0x200 + signal, i * 1_000_000L, signal + i * 0.01, (long) i));
                }
                store.accept(batch);
                keys.add(InMemorySignalCatalogStore.keyOf(batch.getFirst(), measurement, ""));
            }
        }
        long writeMs = millis(writeStarted);
        long last = (SAMPLES_PER_SIGNAL - 1L) * 1_000_000L;
        try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(root)) {
            assertEquals(LOGICAL_SIGNALS, store.keys().size());
            long cold = timeAll(store, keys, last);
            long warm = timeAll(store, keys, last);
            long samples = 0L;
            for (SignalCatalogKey key : keys) {
                long count = store.sampleCount(key);
                assertEquals(SAMPLES_PER_SIGNAL, count);
                samples += count;
                assertEquals(measurement, key.sourceFile());
            }
            SignalSamplePoint first = store.queryRange(keys.getFirst(), 0L, 0L).getFirst();
            SignalSamplePoint lastPoint = store.queryRange(keys.get(119), last, last).getFirst();
            assertEquals(0.0, first.physicalValue(), 0.0);
            assertEquals(119 + 499 * 0.01, lastPoint.physicalValue(), 1e-9);
            note("multiSignal source=synthetic measurement=" + measurement
                    + " logicalSignals=" + LOGICAL_SIGNALS
                    + " samplesPerSignal=" + SAMPLES_PER_SIGNAL
                    + " samples=" + samples
                    + " writeMs=" + writeMs
                    + " coldQueryAllMs=" + cold
                    + " warmQueryAllMs=" + warm);
        }
    }

    private void measureManyRecordings() throws Exception {
        Path root = temp.resolve("recordings");
        long writeStarted = System.nanoTime();
        for (int recording = 0; recording < RECORDINGS; recording++) {
            writeRecording(root.resolve(id(recording)), id(recording));
        }
        long writeMs = millis(writeStarted);
        long cold100 = queryRecordings(root, 100);
        long cold300 = queryRecordings(root, RECORDINGS);
        long warm300 = queryRecordings(root, RECORDINGS);
        note("recordings source=synthetic distinctIdentities=" + RECORDINGS
                + " samplesPerRecording=" + SAMPLES_PER_RECORDING
                + " gpsSamplesPerRecording=" + GPS_SAMPLES
                + " signalsPerRecording=3"
                + " totalSpeedSamples=" + (RECORDINGS * SAMPLES_PER_RECORDING)
                + " writeMs=" + writeMs
                + " cold100Ms=" + cold100
                + " cold300Ms=" + cold300
                + " warm300Ms=" + warm300
                + " prefixNote=the 100-recording timing is identities 000..099 of this same synthetic set");
    }

    private long queryRecordings(Path root, int count) throws Exception {
        long started = System.nanoTime();
        long samples = 0L;
        for (int recording = 0; recording < count; recording++) {
            String measurement = id(recording);
            try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(root.resolve(measurement))) {
                SignalCatalogKey speed = speedKey(measurement);
                assertEquals(SAMPLES_PER_RECORDING, store.sampleCount(speed));
                assertEquals(measurement, speed.sourceFile());
                long last = (SAMPLES_PER_RECORDING - 1L) * 1_000_000L;
                List<GraphBucket> buckets = store.queryBuckets(speed, 0L, last, 40);
                long covered = buckets.stream().mapToLong(GraphBucket::sampleCount).sum();
                assertEquals(SAMPLES_PER_RECORDING, covered);
                samples += covered;
                if (recording == 0 || recording == count - 1) {
                    List<SignalSamplePoint> points = store.queryRange(speed, 0L, last);
                    assertEquals(SAMPLES_PER_RECORDING, points.size());
                    assertEquals(0L, points.getFirst().timestampNanos());
                    assertEquals(recordingSpeed(recording, 0), points.getFirst().physicalValue(), 1e-9);
                    assertEquals(recordingSpeed(recording, SAMPLES_PER_RECORDING - 1),
                            points.getLast().physicalValue(), 1e-9);
                    List<SignalSamplePoint> latitude = store.queryRange(gpsKey(measurement, "SYN_Lat", 0x181), 0L, last);
                    List<SignalSamplePoint> longitude = store.queryRange(gpsKey(measurement, "SYN_Lon", 0x182), 0L, last);
                    assertEquals(GPS_SAMPLES, latitude.size());
                    assertEquals(GPS_SAMPLES, longitude.size());
                    for (int i = 0; i < GPS_SAMPLES; i++) {
                        assertEquals(latitude.get(i).timestampNanos(), longitude.get(i).timestampNanos());
                        assertEquals(latitudeOf(recording, i), latitude.get(i).physicalValue(), 1e-9);
                        assertEquals(longitudeOf(recording, i), longitude.get(i).physicalValue(), 1e-9);
                    }
                }
            }
        }
        assertEquals((long) count * SAMPLES_PER_RECORDING, samples);
        return millis(started);
    }

    private static void writeRecording(Path root, String measurement) throws Exception {
        int index = Integer.parseInt(measurement.substring(measurement.length() - 3));
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, measurement, "", 1_024)) {
            List<CanonicalSignal> batch = new ArrayList<>(SAMPLES_PER_RECORDING + GPS_SAMPLES * 2);
            for (int i = 0; i < SAMPLES_PER_RECORDING; i++) {
                batch.add(numeric("SYN_Speed", 0x180, i * 1_000_000L, recordingSpeed(index, i), (long) i));
            }
            for (int i = 0; i < GPS_SAMPLES; i++) {
                long timestamp = i * 1_000_000L;
                batch.add(numeric("SYN_Lat", 0x181, timestamp, latitudeOf(index, i), null));
                batch.add(numeric("SYN_Lon", 0x182, timestamp, longitudeOf(index, i), null));
            }
            store.accept(batch);
        }
    }

    private static long timeAll(LosslessGraphSignalStore store, List<SignalCatalogKey> keys, long last) {
        long started = System.nanoTime();
        for (SignalCatalogKey key : keys) {
            long covered = store.queryBuckets(key, 0L, last, 40).stream().mapToLong(GraphBucket::sampleCount).sum();
            if (covered != SAMPLES_PER_SIGNAL) {
                throw new AssertionError(key.signalName() + " covered " + covered);
            }
        }
        return millis(started);
    }

    private static int countSegmentFiles(Path root) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith("series-index")) {
                    count.incrementAndGet();
                }
            });
        }
        return count.get();
    }

    static String id(int recording) {
        return String.format("synthetic-m42-%03d", recording);
    }

    static double speedValue(int index) {
        return index == 1_000_000 ? 1_000_000.0 : index % 1000;
    }

    static double recordingSpeed(int recording, int sample) {
        return 20.0 + (recording % 50) + sample * 0.01;
    }

    static double latitudeOf(int recording, int sample) {
        return 18.50 + (recording % 20) * 0.01 + sample * 0.0001;
    }

    static double longitudeOf(int recording, int sample) {
        return 73.70 + (recording / 20) * 0.01 + sample * 0.0001;
    }

    private static SignalCatalogKey speedKey(String measurement) {
        return InMemorySignalCatalogStore.keyOf(numeric("SYN_Speed", 0x180, 0L, 0, 0L), measurement, "");
    }

    private static SignalCatalogKey gpsKey(String measurement, String name, int canId) {
        return InMemorySignalCatalogStore.keyOf(numeric(name, canId, 0L, 0, null), measurement, "");
    }

    private static CanonicalSignal numeric(String name, int canId, long timestamp, double value, Long raw) {
        return new CanonicalSignal(name, "Synthetic", timestamp, canId, 1, value, raw, "unit", null,
                SourceReference.ofLine(1));
    }

    private static CanonicalSignal signal(
            String name, int canId, long timestamp, double value, Long raw, String text, SignalValidity validity
    ) {
        return new CanonicalSignal(name, "Synthetic", timestamp, canId, 1, value, raw, "unit", text,
                SourceReference.ofLine(1), validity);
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private void note(String line) {
        notes.append(line).append('\n');
    }
}
