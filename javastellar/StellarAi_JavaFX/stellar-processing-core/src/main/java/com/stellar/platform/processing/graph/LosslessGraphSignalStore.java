package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Append-only disk-backed graph source. Every accepted timestamp/physical/raw/text sample is persisted;
 * heap use grows with signal/segment metadata, not sample count.
 *
 * <p><b>Write-session model:</b> writers are opened lazily on first append and remain open for the
 * entire processing write-session. There is <em>no</em> mid-session LRU eviction (that caused
 * open/close thrashing under interleaved multi-signal decode). Memory is controlled by a small
 * per-writer {@link BufferedOutputStream} ({@link #SEGMENT_BUFFER_BYTES}, default 16&nbsp;KiB) and by
 * closing all streams in {@link #finishWriteSession()} after processing.</p>
 *
 * <p>With ~745 signals: peak buffer retention ≈ 745 × 16&nbsp;KiB ≈ 12&nbsp;MB during the session,
 * then {@code activeWriterCount() == 0} after finish. The previous 256&nbsp;KiB × 745 ≈ 190&nbsp;MB
 * retention came from leaving large buffers open after processing — not from sample payloads.</p>
 */
public final class LosslessGraphSignalStore implements CancellableLodGraphSeriesQuery, AutoCloseable {
    public static final int DEFAULT_SAMPLES_PER_SEGMENT = 65_536;
    /**
     * Buffer size for each open segment {@link BufferedOutputStream}.
     * Kept small so hundreds of concurrent open writers remain affordable; do not restore 256&nbsp;KiB.
     */
    public static final int SEGMENT_BUFFER_BYTES = 16 * 1024;
    public static final String INDEX_FILE_NAME = "series-index.v1";
    /** Last index whose samples are also recorded in SQLite as a committed processing batch. */
    public static final String COMMITTED_INDEX_FILE_NAME = "series-index.committed";
    private static final int INDEX_MAGIC = 0x534C4958; // SLIX
    /** Samples written before signal validity was stored. Readable, never treated as valid. */
    public static final int INDEX_LAYOUT_LEGACY = 1;
    /** Sample records include a validity byte. This is the layout new stores write. */
    public static final int INDEX_LAYOUT_VALIDITY = 2;
    /** Wire size of one non-text legacy sample (long+double+boolean+long+int). */
    private static final int SAMPLE_FIXED_BYTES_LEGACY = 8 + 8 + 1 + 8 + 4;
    /** Legacy size plus the validity byte. */
    private static final int SAMPLE_FIXED_BYTES = SAMPLE_FIXED_BYTES_LEGACY + 1;

    private final Path root;
    private final String measurementSource;
    private final String definitionSource;
    private final int samplesPerSegment;
    private final int segmentBufferBytes;
    /** Series metadata (always retained for the store lifetime). */
    private final Map<SignalCatalogKey, SeriesWriter> writers = new LinkedHashMap<>();
    /** Guards map structure / closed / dirty / index persistence — not held across segment I/O. */
    private final Object stateLock = new Object();
    private final AtomicInteger openStreamCount = new AtomicInteger();
    private final WriterIoCounters ioCounters = new WriterIoCounters();
    private boolean closed;
    private boolean dirty;
    /** Layout of the sample payloads. New stores write {@link #INDEX_LAYOUT_VALIDITY}. */
    private int sampleLayout = INDEX_LAYOUT_VALIDITY;

    public LosslessGraphSignalStore(Path root, String measurementSource, String definitionSource) {
        this(root, measurementSource, definitionSource, DEFAULT_SAMPLES_PER_SEGMENT, SEGMENT_BUFFER_BYTES);
    }

    public LosslessGraphSignalStore(Path root, String measurementSource, String definitionSource, int samplesPerSegment) {
        this(root, measurementSource, definitionSource, samplesPerSegment, SEGMENT_BUFFER_BYTES);
    }

    public LosslessGraphSignalStore(
            Path root,
            String measurementSource,
            String definitionSource,
            int samplesPerSegment,
            int segmentBufferBytes
    ) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        this.measurementSource = measurementSource == null ? "" : measurementSource;
        this.definitionSource = definitionSource == null ? "" : definitionSource;
        if (samplesPerSegment < 1) throw new IllegalArgumentException("samplesPerSegment must be >= 1");
        if (segmentBufferBytes < 512) throw new IllegalArgumentException("segmentBufferBytes must be >= 512");
        this.samplesPerSegment = samplesPerSegment;
        this.segmentBufferBytes = segmentBufferBytes;
        try { Files.createDirectories(this.root); }
        catch (IOException e) { throw new UncheckedIOException("Cannot create graph store " + this.root, e); }
    }

    /**
     * Reopen an existing project-scoped store from disk. Requires a valid {@link #INDEX_FILE_NAME}.
     * Does not load sample payloads into heap — only catalog/segment metadata.
     */
    public static LosslessGraphSignalStore openExisting(Path root) {
        return openExisting(root, null);
    }

    /**
     * Reopen using a specific index file (resume may prefer {@link #COMMITTED_INDEX_FILE_NAME}
     * over a newer uncheckpointed {@link #INDEX_FILE_NAME}).
     */
    public static LosslessGraphSignalStore openExisting(Path root, Path indexFile) {
        Objects.requireNonNull(root, "root");
        Path absolute = root.toAbsolutePath().normalize();
        Path index = indexFile == null
                ? absolute.resolve(INDEX_FILE_NAME)
                : indexFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(index)) {
            throw new IllegalStateException("Missing lossless signal index: " + index);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != INDEX_MAGIC || !supportedLayout(version)) {
                throw new IllegalStateException("Unsupported lossless index version magic=" + magic + " ver=" + version);
            }
            String measurement = in.readUTF();
            String definition = in.readUTF();
            int samplesPerSegment = in.readInt();
            LosslessGraphSignalStore store = new LosslessGraphSignalStore(
                    absolute, measurement, definition, samplesPerSegment);
            store.sampleLayout = version;
            int seriesCount = in.readInt();
            if (seriesCount < 0 || seriesCount > 5_000_000) {
                throw new IllegalStateException("Corrupt series count: " + seriesCount);
            }
            synchronized (store.stateLock) {
                for (int i = 0; i < seriesCount; i++) {
                    SignalCatalogKey key = new SignalCatalogKey(
                            in.readUTF(),
                            SignalSourceType.valueOf(in.readUTF()),
                            in.readUTF(),
                            in.readLong(),
                            in.readInt(),
                            in.readUTF(),
                            in.readUTF()
                    );
                    String unit = in.readUTF();
                    long totalSamples = in.readLong();
                    Long firstTs = in.readBoolean() ? in.readLong() : null;
                    Long lastTs = in.readBoolean() ? in.readLong() : null;
                    Double minPhys = in.readBoolean() ? in.readDouble() : null;
                    Double maxPhys = in.readBoolean() ? in.readDouble() : null;
                    int seriesId = in.readInt();
                    int segmentCount = in.readInt();
                    List<SegmentMeta> segments = new ArrayList<>(Math.max(0, segmentCount));
                    for (int s = 0; s < segmentCount; s++) {
                        Path relative = Path.of(in.readUTF());
                        Path segmentPath = absolute.resolve(relative).normalize();
                        if (!segmentPath.startsWith(absolute)) {
                            throw new IllegalStateException("Segment path escapes store root: " + relative);
                        }
                        SegmentMeta meta = new SegmentMeta(segmentPath);
                        meta.sampleCount = in.readInt();
                        meta.minTimestamp = in.readLong();
                        meta.maxTimestamp = in.readLong();
                        segments.add(meta);
                    }
                    SeriesWriter writer = store.new SeriesWriter(key, unit, seriesId);
                    writer.totalSamples = totalSamples;
                    writer.firstTs = firstTs;
                    writer.lastTs = lastTs;
                    writer.minPhys = minPhys;
                    writer.maxPhys = maxPhys;
                    writer.segments.addAll(segments);
                    writer.nextSegment = segments.size();
                    writer.bindCurrentSegmentAfterLoad();
                    store.writers.put(key, writer);
                }
                store.dirty = false;
            }
            store.truncateUnindexedBytes();
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open lossless signal store " + absolute, e);
        }
    }

    public Path root() {
        return root;
    }

    public String measurementSource() {
        return measurementSource;
    }

    public String definitionSource() {
        return definitionSource;
    }

    public boolean hasIndex() {
        return Files.isRegularFile(root.resolve(INDEX_FILE_NAME));
    }

    /**
     * Layout version stored in the index, or {@code -1} when the index is missing or not a
     * recognized lossless index. Version {@link #INDEX_LAYOUT_LEGACY} has no validity byte.
     */
    public static int indexLayoutVersion(Path root) {
        if (root == null) {
            return -1;
        }
        Path index = indexFile(root);
        if (!Files.isRegularFile(index)) {
            return -1;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != INDEX_MAGIC || !supportedLayout(version)) {
                return -1;
            }
            return version;
        } catch (IOException ex) {
            return -1;
        }
    }

    /**
     * True when the index is the validity layout and every segment's bytes decode as that
     * layout for the indexed sample count. A layout 1 index, a missing segment, or a segment
     * whose records were written with a different layout returns false. Read-only.
     */
    public static boolean payloadMatchesValidityLayout(Path root) {
        return layoutMismatch(root) == null;
    }

    private static String describeStore(Path root, Path index) {
        String names = "(unreadable)";
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            names = children.map(path -> path.getFileName().toString()).toList().toString();
        } catch (IOException ex) {
            names = ex.getClass().getSimpleName();
        }
        long size = -1L;
        try {
            if (Files.isRegularFile(index)) {
                size = Files.size(index);
            }
        } catch (IOException ignored) {
            size = -1L;
        }
        return "index=" + index.getFileName() + " exists=" + Files.isRegularFile(index)
                + " size=" + size + " entries=" + names;
    }

    /** Null when the index and segment bytes agree. Otherwise the first disagreement. */
    static String layoutMismatch(Path root) {
        Path index = indexFile(root);
        if (indexLayoutVersion(root) != INDEX_LAYOUT_VALIDITY) {
            return "index layout is " + indexLayoutVersion(root) + ", expected " + INDEX_LAYOUT_VALIDITY
                    + "; " + describeStore(root, index);
        }
        if (!Files.isRegularFile(index)) {
            return "index file missing; " + describeStore(root, index);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != INDEX_MAGIC || version != INDEX_LAYOUT_VALIDITY) {
                return "index magic/layout " + magic + "/" + version;
            }
            in.readUTF();
            in.readUTF();
            in.readInt();
            int seriesCount = in.readInt();
            if (seriesCount < 0 || seriesCount > 5_000_000) {
                return "series count " + seriesCount;
            }
            Path absolute = root.toAbsolutePath().normalize();
            for (int i = 0; i < seriesCount; i++) {
                String signal = in.readUTF();
                in.readUTF();
                in.readUTF();
                in.readLong();
                in.readInt();
                in.readUTF();
                in.readUTF();
                in.readUTF();
                in.readLong();
                if (in.readBoolean()) in.readLong();
                if (in.readBoolean()) in.readLong();
                if (in.readBoolean()) in.readDouble();
                if (in.readBoolean()) in.readDouble();
                in.readInt();
                int segmentCount = in.readInt();
                if (segmentCount < 0) {
                    return signal + " segment count " + segmentCount;
                }
                for (int s = 0; s < segmentCount; s++) {
                    String relative = in.readUTF();
                    Path segment = absolute.resolve(relative).normalize();
                    int sampleCount = in.readInt();
                    in.readLong();
                    in.readLong();
                    if (!segment.startsWith(absolute)) {
                        return signal + " segment escapes the store: " + segment;
                    }
                    if (sampleCount <= 0) {
                        continue;
                    }
                    if (!Files.isRegularFile(segment)) {
                        return signal + " segment missing: " + relative;
                    }
                    long declared = indexedSegmentBytes(segment, sampleCount, INDEX_LAYOUT_VALIDITY);
                    long actual = Files.size(segment);
                    if (declared != actual) {
                        return signal + " segment " + relative + " decoded " + declared
                                + " bytes for " + sampleCount + " samples but the file is " + actual;
                    }
                }
            }
            return null;
        } catch (IOException | RuntimeException ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private static Path indexFile(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        Path index = absolute.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index) && Files.isRegularFile(absolute.resolve(COMMITTED_INDEX_FILE_NAME))) {
            return absolute.resolve(COMMITTED_INDEX_FILE_NAME);
        }
        return index;
    }

    private static boolean supportedLayout(int version) {
        return version == INDEX_LAYOUT_LEGACY || version == INDEX_LAYOUT_VALIDITY;
    }

    /** True when {@code root} contains a readable {@link #INDEX_FILE_NAME} or committed index. */
    public static boolean hasValidIndex(Path root) {
        if (root == null) {
            return false;
        }
        Path absolute = root.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute.resolve(INDEX_FILE_NAME))
                || Files.isRegularFile(absolute.resolve(COMMITTED_INDEX_FILE_NAME));
    }

    /**
     * Choose the index whose stored sample total equals {@code ledgerSamples}.
     * Used so resume never reads an uncheckpointed generation or skips a checkpointed one.
     */
    public static Path indexMatchingSampleCount(Path root, long ledgerSamples) {
        Objects.requireNonNull(root, "root");
        Path absolute = root.toAbsolutePath().normalize();
        Path v1 = absolute.resolve(INDEX_FILE_NAME);
        Path committed = absolute.resolve(COMMITTED_INDEX_FILE_NAME);
        Long v1Samples = peekTotalSamples(v1);
        Long committedSamples = peekTotalSamples(committed);
        if (v1Samples != null && v1Samples == ledgerSamples) {
            return v1;
        }
        if (committedSamples != null && committedSamples == ledgerSamples) {
            return committed;
        }
        return null;
    }

    public static Long peekTotalSamples(Path indexFile) {
        if (indexFile == null || !Files.isRegularFile(indexFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != INDEX_MAGIC || !supportedLayout(version)) {
                return null;
            }
            in.readUTF();
            in.readUTF();
            in.readInt();
            int seriesCount = in.readInt();
            if (seriesCount < 0 || seriesCount > 5_000_000) {
                return null;
            }
            long total = 0L;
            for (int i = 0; i < seriesCount; i++) {
                in.readUTF();
                in.readUTF();
                in.readUTF();
                in.readLong();
                in.readInt();
                in.readUTF();
                in.readUTF();
                in.readUTF();
                total += in.readLong();
                if (in.readBoolean()) {
                    in.readLong();
                }
                if (in.readBoolean()) {
                    in.readLong();
                }
                if (in.readBoolean()) {
                    in.readDouble();
                }
                if (in.readBoolean()) {
                    in.readDouble();
                }
                in.readInt();
                int segmentCount = in.readInt();
                for (int s = 0; s < segmentCount; s++) {
                    in.readUTF();
                    in.readInt();
                    in.readLong();
                    in.readLong();
                }
            }
            return total;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * True when the index exists and every referenced segment file is present on disk.
     * An index without payloads is not reusable — seeding it produces empty Explore Signals tables.
     */
    public static boolean segmentsReadable(Path root) {
        if (!hasValidIndex(root)) {
            return false;
        }
        Path absolute = root.toAbsolutePath().normalize();
        Path index = absolute.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index) && Files.isRegularFile(absolute.resolve(COMMITTED_INDEX_FILE_NAME))) {
            index = absolute.resolve(COMMITTED_INDEX_FILE_NAME);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != INDEX_MAGIC || !supportedLayout(version)) {
                return false;
            }
            in.readUTF(); // measurement
            in.readUTF(); // definition
            in.readInt(); // samplesPerSegment
            int seriesCount = in.readInt();
            if (seriesCount < 0) {
                return false;
            }
            for (int i = 0; i < seriesCount; i++) {
                in.readUTF(); // signalName
                in.readUTF(); // sourceType
                in.readUTF(); // messageName
                in.readLong(); // canId
                in.readInt(); // channel
                in.readUTF(); // sourceFile
                in.readUTF(); // sourceDatabase
                in.readUTF(); // unit
                in.readLong(); // totalSamples
                if (in.readBoolean()) {
                    in.readLong();
                }
                if (in.readBoolean()) {
                    in.readLong();
                }
                if (in.readBoolean()) {
                    in.readDouble();
                }
                if (in.readBoolean()) {
                    in.readDouble();
                }
                in.readInt(); // seriesId
                int segmentCount = in.readInt();
                if (segmentCount < 0) {
                    return false;
                }
                for (int s = 0; s < segmentCount; s++) {
                    Path relative = Path.of(in.readUTF());
                    Path segmentPath = absolute.resolve(relative).normalize();
                    in.readInt();
                    in.readLong();
                    in.readLong();
                    if (!Files.isRegularFile(segmentPath)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    public void accept(List<CanonicalSignal> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) return;
        for (CanonicalSignal signal : batch) {
            SeriesWriter writer;
            synchronized (stateLock) {
                ensureOpen();
                SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(signal, measurementSource, definitionSource);
                writer = writers.computeIfAbsent(key, k -> new SeriesWriter(k, signal.unit(), writers.size() + 1));
                dirty = true;
            }
            // I/O under per-writer lock only — never hold stateLock across FileChannel open/write/flush.
            writer.append(signal);
        }
    }

    @Override public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
        return queryRange(key, startNanos, endNanos, Integer.MAX_VALUE, () -> true);
    }

    @Override public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, int maxSamples) {
        return queryRange(key, startNanos, endNanos, maxSamples, () -> true);
    }

    @Override public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key, long startNanos, long endNanos, BooleanSupplier continueQuery) {
        return queryRange(key, startNanos, endNanos, Integer.MAX_VALUE, continueQuery);
    }

    @Override
    public void forEachSample(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            GraphSeriesQuery.SampleConsumer consumer,
            BooleanSupplier continueQuery
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(consumer, "consumer");
        BooleanSupplier cont = continueQuery == null ? () -> true : continueQuery;
        if (endNanos < startNanos) {
            throw new IllegalArgumentException("endNanos must be >= startNanos");
        }
        SeriesWriter writer = findWriter(key);
        if (writer == null) {
            return;
        }
        writer.flushCurrent();
        AtomicBoolean stop = new AtomicBoolean();
        for (SegmentMeta segment : writer.segmentSnapshot()) {
            if (stop.get() || !cont.getAsBoolean()) {
                return;
            }
            if (segment.maxTimestamp < startNanos || segment.minTimestamp > endNanos) {
                continue;
            }
            scanUntil(segment.path, sampleLayout, point -> {
                if (point.timestampNanos() >= startNanos && point.timestampNanos() <= endNanos) {
                    if (!consumer.accept(point)) {
                        stop.set(true);
                        return false;
                    }
                }
                return true;
            }, () -> cont.getAsBoolean() && !stop.get());
        }
    }

    public List<SignalSamplePoint> queryRange(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            int maxSamples,
            BooleanSupplier continueQuery
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(continueQuery, "continueQuery");
        if (endNanos < startNanos) throw new IllegalArgumentException("endNanos must be >= startNanos");
        int limit = Math.max(1, maxSamples);
        SeriesWriter writer = findWriter(key);
        if (writer == null) return List.of();
        writer.flushCurrent();
        List<SegmentMeta> segments = writer.segmentSnapshot();
        List<SignalSamplePoint> out = new ArrayList<>(Math.min(limit, 4096));
        for (SegmentMeta segment : segments) {
            checkCancelled(continueQuery);
            if (out.size() >= limit) break;
            if (segment.maxTimestamp < startNanos || segment.minTimestamp > endNanos) continue;
            readRange(segment.path, sampleLayout, startNanos, endNanos, out, limit, continueQuery);
        }
        return List.copyOf(out);
    }

    /**
     * Reads each segment file once from start to end. A paged {@link #querySamples} reopens the
     * file and skips from the first record, which is quadratic for a full-series walk.
     */
    public void forEachSample(SignalCatalogKey key, java.util.function.Consumer<SignalSamplePoint> consumer) {
        Objects.requireNonNull(key, "key");
        if (consumer == null) {
            return;
        }
        SeriesWriter writer = findWriter(key);
        if (writer == null) {
            return;
        }
        writer.flushCurrent();
        for (SegmentMeta segment : writer.segmentSnapshot()) {
            scan(segment.path, sampleLayout, consumer, () -> true);
        }
    }

    public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        Objects.requireNonNull(key, "key");
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        SeriesWriter writer = findWriter(key);
        if (writer == null || safeOffset >= writer.sampleCountVolatile()) return List.of();
        writer.flushCurrent();
        List<SegmentMeta> segments = writer.segmentSnapshot();
        List<SignalSamplePoint> out = new ArrayList<>(Math.min(safeLimit, 4096));
        long remainingSkip = safeOffset;
        for (SegmentMeta segment : segments) {
            if (out.size() >= safeLimit) break;
            if (remainingSkip >= segment.sampleCount) {
                remainingSkip -= segment.sampleCount;
                continue;
            }
            readPage(segment.path, remainingSkip, safeLimit - out.size(), out, sampleLayout);
            remainingSkip = 0L;
        }
        return List.copyOf(out);
    }

    @Override public List<GraphBucket> queryBuckets(SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets) {
        return queryBuckets(key, startNanos, endNanos, targetBuckets, () -> true);
    }

    @Override public List<GraphBucket> queryBuckets(
            SignalCatalogKey key, long startNanos, long endNanos, int targetBuckets, BooleanSupplier continueQuery) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(continueQuery, "continueQuery");
        if (endNanos < startNanos) throw new IllegalArgumentException("endNanos must be >= startNanos");
        int bucketCount = Math.max(1, targetBuckets);
        if (startNanos == endNanos) {
            List<SignalSamplePoint> exact = queryRange(key, startNanos, endNanos, continueQuery);
            return MinMaxLodReducer.reduce(exact, bucketCount);
        }
        SeriesWriter writer = findWriter(key);
        if (writer == null) return List.of();
        writer.flushCurrent();
        List<SegmentMeta> segments = writer.segmentSnapshot();
        BucketAccumulator[] acc = new BucketAccumulator[bucketCount];
        long span = endNanos - startNanos;
        for (SegmentMeta segment : segments) {
            checkCancelled(continueQuery);
            if (segment.maxTimestamp < startNanos || segment.minTimestamp > endNanos) continue;
            scan(segment.path, sampleLayout, point -> {
                long ts = point.timestampNanos();
                if (ts < startNanos || ts > endNanos) return;
                long relative = ts - startNanos;
                int index = relative == span ? bucketCount - 1
                        : (int) Math.min(bucketCount - 1, (long) Math.floor((relative / (double) span) * bucketCount));
                BucketAccumulator a = acc[index];
                if (a == null) acc[index] = a = new BucketAccumulator();
                a.accept(point);
            }, continueQuery);
        }
        List<GraphBucket> out = new ArrayList<>(bucketCount);
        for (BucketAccumulator a : acc) if (a != null) out.add(a.toBucket());
        return List.copyOf(out);
    }

    @Override public String unit(SignalCatalogKey key) {
        SeriesWriter w = findWriter(key);
        return w == null ? "" : w.unit;
    }

    @Override public long sampleCount(SignalCatalogKey key) {
        SeriesWriter w = findWriter(key);
        return w == null ? 0L : w.sampleCountVolatile();
    }

    public List<SignalCatalogKey> keys() {
        synchronized (stateLock) {
            return List.copyOf(writers.keySet());
        }
    }

    /** Catalog metadata only — sample payloads remain on disk. */
    public List<SignalCatalogEntry> catalogEntries() {
        synchronized (stateLock) {
            List<SignalCatalogEntry> entries = new ArrayList<>(writers.size());
            for (SeriesWriter writer : writers.values()) {
                entries.add(writer.toEntry());
            }
            return List.copyOf(entries);
        }
    }

    public long totalSampleCount() {
        synchronized (stateLock) {
            long total = 0L;
            for (SeriesWriter writer : writers.values()) total += writer.sampleCountVolatile();
            return total;
        }
    }

    /**
     * Number of series that currently hold an open output stream.
     * Metadata entries without an open stream are not counted.
     */
    public int activeWriterCount() {
        return openStreamCount.get();
    }

    public int segmentBufferBytes() {
        return segmentBufferBytes;
    }

    /** Development/test instrumentation snapshot for the current store instance. */
    public WriterIoStats writerIoStats() {
        return ioCounters.snapshot();
    }

    public void resetWriterIoStats() {
        ioCounters.reset();
    }

    /**
     * Flush and close every open series stream, then persist {@link #INDEX_FILE_NAME}
     * when this session has accepted samples. A store that was only reopened must not
     * rewrite the index: a newer generation may already have replaced the files.
     * Does <em>not</em> delete artifacts or mark the store closed — queries and incremental
     * {@link #accept(List)} remain available (writers reopen in append/segment mode as needed).
     */
    public void finishWriteSession() {
        closeAllWriters();
        synchronized (stateLock) {
            // A completed job that decoded no samples still owns a validity-layout artifact.
            // Reopening an existing index must not rewrite it.
            if (!closed && writers.isEmpty() && !Files.isRegularFile(root.resolve(INDEX_FILE_NAME))) {
                dirty = true;
                persistIndexUnlocked();
            }
        }
    }

    /**
     * Close all open series output streams without deleting series data.
     * Persists the index only when this session accepted samples or still holds a writer.
     */
    public void closeAllWriters() {
        List<SeriesWriter> snapshot;
        boolean persist;
        synchronized (stateLock) {
            ensureOpen();
            snapshot = List.copyOf(writers.values());
            boolean openStreams = false;
            for (SeriesWriter writer : snapshot) {
                if (writer.hasOpenStream()) {
                    openStreams = true;
                    break;
                }
            }
            persist = dirty || openStreams;
            if (persist) {
                dirty = true;
            }
        }
        IOException failure = null;
        for (SeriesWriter writer : snapshot) {
            try {
                writer.closeCurrent();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        synchronized (stateLock) {
            if (persist) {
                persistIndexUnlocked();
            }
        }
        if (failure != null) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Flush open segments and write {@link #INDEX_FILE_NAME} so the store can be reopened later. */
    public void persistIndex() {
        List<SeriesWriter> snapshot;
        synchronized (stateLock) {
            ensureOpen();
            snapshot = List.copyOf(writers.values());
            dirty = true;
        }
        for (SeriesWriter writer : snapshot) {
            writer.flushCurrent();
        }
        synchronized (stateLock) {
            persistIndexUnlocked();
        }
    }

    /**
     * Copies {@link #INDEX_FILE_NAME} to {@link #COMMITTED_INDEX_FILE_NAME} after SQLite has
     * recorded the matching processing batch. Crash recovery prefers the index whose sample
     * total matches the ledger.
     */
    public void promoteCommittedIndex() {
        persistIndex();
        Path v1 = root.resolve(INDEX_FILE_NAME);
        Path committed = root.resolve(COMMITTED_INDEX_FILE_NAME);
        try {
            if (!Files.isRegularFile(v1)) {
                return;
            }
            Files.copy(v1, committed, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot promote committed lossless index", e);
        }
    }

    private void truncateUnindexedBytes() {
        synchronized (stateLock) {
            for (SeriesWriter writer : writers.values()) {
                writer.truncateToIndexedBytes();
            }
        }
    }

    @Override public void close() {
        List<SeriesWriter> snapshot;
        synchronized (stateLock) {
            if (closed) return;
            snapshot = List.copyOf(writers.values());
        }
        IOException failure = null;
        for (SeriesWriter writer : snapshot) {
            try {
                writer.closeCurrent();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        synchronized (stateLock) {
            try {
                persistIndexUnlocked();
            } catch (RuntimeException e) {
                if (failure == null && e.getCause() instanceof IOException io) failure = io;
                else if (failure == null) failure = new IOException(e);
            }
            closed = true;
        }
        if (failure != null) throw new UncheckedIOException(failure);
    }

    /** Read path: closed stores return null so callers can yield empty results without crashing. */
    private SeriesWriter findWriter(SignalCatalogKey key) {
        synchronized (stateLock) {
            if (closed) {
                return null;
            }
            return writers.get(key);
        }
    }

    private SeriesWriter requireWriter(SignalCatalogKey key) {
        synchronized (stateLock) {
            ensureOpen();
            return writers.get(key);
        }
    }

    private void persistIndexUnlocked() {
        if (!dirty && Files.isRegularFile(root.resolve(INDEX_FILE_NAME))) return;
        Path target = root.resolve(INDEX_FILE_NAME);
        Path temp = root.resolve(INDEX_FILE_NAME + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            out.writeInt(INDEX_MAGIC);
            out.writeInt(sampleLayout);
            out.writeUTF(measurementSource);
            out.writeUTF(definitionSource);
            out.writeInt(samplesPerSegment);
            out.writeInt(writers.size());
            for (SeriesWriter writer : writers.values()) {
                writer.flushCurrent();
                SignalCatalogKey key = writer.key;
                out.writeUTF(key.signalName());
                out.writeUTF(key.sourceType().name());
                out.writeUTF(key.messageName());
                out.writeLong(key.canId());
                out.writeInt(key.channel());
                out.writeUTF(key.sourceFile());
                out.writeUTF(key.sourceDatabase());
                out.writeUTF(writer.unit);
                out.writeLong(writer.sampleCountVolatile());
                Long firstTs = writer.firstTs;
                Long lastTs = writer.lastTs;
                Double minPhys = writer.minPhys;
                Double maxPhys = writer.maxPhys;
                out.writeBoolean(firstTs != null);
                if (firstTs != null) out.writeLong(firstTs);
                out.writeBoolean(lastTs != null);
                if (lastTs != null) out.writeLong(lastTs);
                out.writeBoolean(minPhys != null);
                if (minPhys != null) out.writeDouble(minPhys);
                out.writeBoolean(maxPhys != null);
                if (maxPhys != null) out.writeDouble(maxPhys);
                out.writeInt(writer.seriesId);
                List<SegmentMeta> segments = writer.segmentSnapshot();
                out.writeInt(segments.size());
                for (SegmentMeta segment : segments) {
                    Path relative = root.relativize(segment.path.toAbsolutePath().normalize());
                    out.writeUTF(relative.toString().replace('\\', '/'));
                    out.writeInt(segment.sampleCount);
                    out.writeLong(segment.minTimestamp);
                    out.writeLong(segment.maxTimestamp);
                }
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write lossless index " + target, e);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot publish lossless index " + target, e);
            }
        }
        dirty = false;
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("graph store is closed"); }

    private static void checkCancelled(BooleanSupplier continueQuery) {
        if (!continueQuery.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Obsolete graph query");
        }
    }

    /**
     * Appends at most {@code limit} records from this segment. {@code limit} is the number of
     * records to add, not a cap on {@code out}: {@link #querySamples} reuses one list across
     * segments and passes only the records still required.
     */
    private static void readPage(Path path, long skip, int limit, List<SignalSamplePoint> out, int layout) {
        if (limit <= 0) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            long index = 0L;
            int appended = 0;
            while (appended < limit) {
                SignalSamplePoint point = readRecord(in, layout);
                if (point == null) {
                    break;
                }
                if (index++ < skip) {
                    continue;
                }
                out.add(point);
                appended++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read graph sample page " + path, e);
        }
    }

    private static void readRange(Path path, int layout, long start, long end, List<SignalSamplePoint> out,
                                  int maxSamples, BooleanSupplier continueQuery) {
        int limit = Math.max(1, maxSamples);
        scanUntil(path, layout, point -> {
            if (point.timestampNanos() >= start && point.timestampNanos() <= end) {
                out.add(point);
            }
            return out.size() < limit;
        }, continueQuery);
        if (out.size() < limit && !continueQuery.getAsBoolean()) {
            throw new CancellationException("Obsolete graph query");
        }
    }

    private static void scanUntil(Path path,
                                  int layout,
                                  java.util.function.Function<SignalSamplePoint, Boolean> consumer,
                                  BooleanSupplier continueQuery) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int cancellationProbe = 0;
            while (true) {
                if ((cancellationProbe++ & 0x3ff) == 0 && !continueQuery.getAsBoolean()) {
                    return;
                }
                SignalSamplePoint point = readRecord(in, layout);
                if (point == null) {
                    break;
                }
                if (!consumer.apply(point)) {
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read graph segment " + path, e);
        }
    }

    private static void scan(Path path, int layout, java.util.function.Consumer<SignalSamplePoint> consumer,
                             BooleanSupplier continueQuery) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int cancellationProbe = 0;
            while (true) {
                if ((cancellationProbe++ & 0x3ff) == 0) {
                    checkCancelled(continueQuery);
                }
                SignalSamplePoint point = readRecord(in, layout);
                if (point == null) {
                    break;
                }
                consumer.accept(point);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read graph segment " + path, e);
        }
    }

    /**
     * Reads one sample. Legacy layout has no validity byte and is returned as
     * {@link com.stellar.platform.domain.SignalValidity#UNRECORDED}. A clean end of file
     * before the timestamp returns null. A truncated record fails.
     */
    public static SignalSamplePoint readRecord(DataInputStream in, int layout) throws IOException {
        long ts;
        try {
            ts = in.readLong();
        } catch (EOFException end) {
            return null;
        }
        double physical = in.readDouble();
        boolean hasRaw = in.readBoolean();
        long rawEncoded = in.readLong();
        int textLength = in.readInt();
        if (textLength < -1 || textLength > 16 * 1024 * 1024) {
            throw new IOException("Invalid text length " + textLength);
        }
        String text = null;
        if (textLength >= 0) {
            byte[] bytes = in.readNBytes(textLength);
            if (bytes.length != textLength) {
                throw new EOFException("Truncated textual signal value");
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        com.stellar.platform.domain.SignalValidity validity = com.stellar.platform.domain.SignalValidity.UNRECORDED;
        if (layout >= INDEX_LAYOUT_VALIDITY) {
            int code = in.readUnsignedByte();
            validity = com.stellar.platform.domain.SignalValidity.fromStoredCode(code);
        }
        return new SignalSamplePoint(ts, physical, hasRaw ? rawEncoded : null, text, validity);
    }

    /**
     * Immutable snapshot of writer I/O counters for tests / diagnostics.
     * {@code evictions} remains 0 — mid-session LRU eviction was removed.
     */
    public record WriterIoStats(
            long opens,
            long closes,
            long evictions,
            long reopens,
            long appends,
            long bytesWritten,
            long flushes
    ) {
        public boolean indicatesThrashing(int logicalSignalCount) {
            // Opens far above logical signal count (allowing modest segment rotations) ⇒ thrashing.
            return opens > Math.max(64L, logicalSignalCount * 3L);
        }
    }

    private static final class WriterIoCounters {
        private final AtomicLong opens = new AtomicLong();
        private final AtomicLong closes = new AtomicLong();
        private final AtomicLong evictions = new AtomicLong();
        private final AtomicLong reopens = new AtomicLong();
        private final AtomicLong appends = new AtomicLong();
        private final AtomicLong bytesWritten = new AtomicLong();
        private final AtomicLong flushes = new AtomicLong();

        WriterIoStats snapshot() {
            return new WriterIoStats(
                    opens.get(), closes.get(), evictions.get(), reopens.get(),
                    appends.get(), bytesWritten.get(), flushes.get());
        }

        void reset() {
            opens.set(0);
            closes.set(0);
            evictions.set(0);
            reopens.set(0);
            appends.set(0);
            bytesWritten.set(0);
            flushes.set(0);
        }
    }

    private final class SeriesWriter {
        final SignalCatalogKey key;
        final String unit;
        final int seriesId;
        private final List<SegmentMeta> segments = new ArrayList<>();
        private DataOutputStream out;
        private SegmentMeta current;
        private volatile long totalSamples;
        private int nextSegment;
        private volatile Long firstTs;
        private volatile Long lastTs;
        private volatile Double minPhys;
        private volatile Double maxPhys;
        private boolean everOpened;

        SeriesWriter(SignalCatalogKey key, String unit, int seriesId) {
            this.key = key;
            this.unit = unit == null ? "" : unit;
            this.seriesId = seriesId;
        }

        void bindCurrentSegmentAfterLoad() {
            if (segments.isEmpty()) {
                current = null;
                return;
            }
            SegmentMeta last = segments.get(segments.size() - 1);
            current = last.sampleCount < samplesPerSegment ? last : null;
        }

        synchronized void append(CanonicalSignal signal) {
            if (sampleLayout < INDEX_LAYOUT_VALIDITY) {
                throw new IllegalStateException(
                        "Analysis artifact has no signal-validity record — reprocessing required");
            }
            try {
                ensureOpenForWrite();
                byte[] text = signal.textualValue() == null ? null : signal.textualValue().getBytes(StandardCharsets.UTF_8);
                out.writeLong(signal.timestampNanos());
                out.writeDouble(signal.physicalValue());
                out.writeBoolean(signal.rawValue() != null);
                out.writeLong(signal.rawValue() == null ? 0L : signal.rawValue());
                out.writeInt(text == null ? -1 : text.length);
                if (text != null) out.write(text);
                out.writeByte(signal.validity().storedCode());
                current.include(signal.timestampNanos());
                totalSamples++;
                long ts = signal.timestampNanos();
                if (firstTs == null) firstTs = ts;
                lastTs = ts;
                if (signal.usableMeasurement()) {
                    double p = signal.physicalValue();
                    if (minPhys == null || p < minPhys) minPhys = p;
                    if (maxPhys == null || p > maxPhys) maxPhys = p;
                }
                ioCounters.appends.incrementAndGet();
                ioCounters.bytesWritten.addAndGet(SAMPLE_FIXED_BYTES + (text == null ? 0 : text.length));
            } catch (IOException e) { throw new UncheckedIOException("Cannot append graph sample", e); }
        }

        private void ensureOpenForWrite() throws IOException {
            if (out != null) {
                if (current != null && current.sampleCount >= samplesPerSegment) {
                    rotateNewSegment();
                }
                return;
            }
            // Session-scoped: open once and stay open until finishWriteSession / segment rotate.
            if (current != null && current.sampleCount < samplesPerSegment) {
                openStream(current.path, true);
            } else {
                rotateNewSegment();
            }
        }

        private void rotateNewSegment() throws IOException {
            closeCurrentUnlocked();
            Path dir = root.resolve(String.format("series-%06d", seriesId));
            Files.createDirectories(dir);
            Path path = dir.resolve(String.format("segment-%06d.bin", nextSegment++));
            current = new SegmentMeta(path);
            segments.add(current);
            openStream(path, false);
        }

        private void openStream(Path path, boolean append) throws IOException {
            if (everOpened) {
                ioCounters.reopens.incrementAndGet();
            }
            ioCounters.opens.incrementAndGet();
            if (append) {
                long keep = indexedSegmentBytes(path, current == null ? 0 : current.sampleCount, sampleLayout);
                FileChannel channel = FileChannel.open(path,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                if (channel.size() != keep) {
                    channel.truncate(keep);
                }
                channel.position(keep);
                out = new DataOutputStream(new BufferedOutputStream(
                        Channels.newOutputStream(channel), segmentBufferBytes));
            } else {
                out = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        segmentBufferBytes));
            }
            everOpened = true;
            openStreamCount.incrementAndGet();
        }

        void truncateToIndexedBytes() {
            for (SegmentMeta segment : segments) {
                try {
                    long keep = indexedSegmentBytes(segment.path, segment.sampleCount, sampleLayout);
                    if (Files.isRegularFile(segment.path)) {
                        try (FileChannel channel = FileChannel.open(segment.path,
                                StandardOpenOption.WRITE, StandardOpenOption.READ)) {
                            if (channel.size() > keep) {
                                channel.truncate(keep);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot truncate unindexed graph samples " + segment.path, e);
                }
            }
        }

        synchronized void flushCurrent() {
            if (out == null) return;
            try {
                out.flush();
                ioCounters.flushes.incrementAndGet();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        synchronized void closeCurrent() throws IOException {
            closeCurrentUnlocked();
        }

        synchronized boolean hasOpenStream() {
            return out != null;
        }

        private void closeCurrentUnlocked() throws IOException {
            if (out == null) return;
            try {
                out.flush();
                ioCounters.flushes.incrementAndGet();
                out.close();
                ioCounters.closes.incrementAndGet();
            } finally {
                out = null;
                openStreamCount.decrementAndGet();
            }
        }

        synchronized List<SegmentMeta> segmentSnapshot() {
            return List.copyOf(segments);
        }

        long sampleCountVolatile() {
            return totalSamples;
        }

        synchronized SignalCatalogEntry toEntry() {
            return new SignalCatalogEntry(
                    key, unit, totalSamples, firstTs, lastTs, minPhys, maxPhys,
                    key.sourceFile(), key.sourceDatabase());
        }
    }

    private static final class BucketAccumulator {
        SignalSamplePoint first, last, min, max;
        long count;
        void accept(SignalSamplePoint p) {
            if (first == null) {
                first = p;
            }
            last = p;
            if (p.usableMeasurement()) {
                if (min == null || p.physicalValue() < min.physicalValue()) {
                    min = p;
                }
                if (max == null || p.physicalValue() > max.physicalValue()) {
                    max = p;
                }
            }
            count++;
        }
        GraphBucket toBucket() {
            return MinMaxLodReducer.fromCorners(first, last, min, max, count);
        }
    }

    private static final class SegmentMeta {
        final Path path;
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;
        int sampleCount;
        SegmentMeta(Path path) { this.path = path; }
        void include(long timestamp) {
            minTimestamp = Math.min(minTimestamp, timestamp);
            maxTimestamp = Math.max(maxTimestamp, timestamp);
            sampleCount++;
        }
    }

    static long indexedSegmentBytes(Path path, int sampleCount, int layout) throws IOException {
        if (sampleCount <= 0 || path == null || !Files.isRegularFile(path)) {
            return 0L;
        }
        int fixed = layout >= INDEX_LAYOUT_VALIDITY ? SAMPLE_FIXED_BYTES : SAMPLE_FIXED_BYTES_LEGACY;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            long bytes = 0L;
            for (int i = 0; i < sampleCount; i++) {
                in.readLong();
                in.readDouble();
                in.readBoolean();
                in.readLong();
                int textLen = in.readInt();
                bytes += fixed;
                if (textLen > 0) {
                    in.skipNBytes(textLen);
                    bytes += textLen;
                }
                if (layout >= INDEX_LAYOUT_VALIDITY) {
                    in.readUnsignedByte();
                }
            }
            return bytes;
        }
    }
}
