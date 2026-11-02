package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.BatchSignalConsumer;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One processing-result signal session with two deliberately different views:
 * a bounded preview store for the existing Signals UI and a lossless disk-backed
 * graph store for engineering analysis. A decoded batch is fanned out once to both.
 *
 * <p><b>Root cause (project reopen):</b> {@link #create(String, String)} historically used a
 * random temp directory with {@code deleteRootOnClose=true}. After restart those samples were gone
 * and {@code openSavedProject} cleared the registry, so Signals showed 0. Persistent project roots
 * ({@link #createPersistent(Path, String, String)} / {@link #openExisting(Path)}) keep the same
 * {@link SignalSeriesStore} API without storing samples in SQLite.</p>
 */
public final class GraphSignalSessionStore implements SignalSeriesStore, BatchSignalConsumer, GraphQueryProvider, AutoCloseable {
    public static final String INCOMPLETE_FLAG = "incomplete.flag";
    /** Sidecar written by new stores. Sample records stay on the validity layout. */
    public static final String DECODER_GENERATION_FILE = "decoder-generation";
    /** Must stay equal to {@code ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT}. */
    public static final String DECODER_GENERATION = "3";
    private final InMemorySignalCatalogStore preview;
    private final LosslessGraphSignalStore graph;
    private final Path root;
    private final boolean deleteRootOnClose;

    public static GraphSignalSessionStore create(String measurementSource, String definitionSource) {
        try {
            Path base = transientSessionBase();
            Files.createDirectories(base);
            Path root = base.resolve(UUID.randomUUID().toString());
            return new GraphSignalSessionStore(root, measurementSource, definitionSource, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create graph session", e);
        }
    }

    /**
     * Transient graph scratch. After Analysis Storage is configured this is
     * {@code StellarAIAnalysis/temp/graph-sessions}. An unavailable configured temp directory
     * fails here and is not replaced with the operating-system temp directory.
     */
    private static Path transientSessionBase() {
        String configured = System.getProperty("stellarai.runtime.temp", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).resolve("graph-sessions");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "stellarai", "graph-sessions");
    }

    /** Create (or overwrite) a durable project-scoped session. Root is retained on close. */
    public static GraphSignalSessionStore createPersistent(
            Path root,
            String measurementSource,
            String definitionSource
    ) {
        Objects.requireNonNull(root, "root");
        try {
            if (Files.exists(root)) {
                deleteTreeBestEffort(root);
            }
            Files.createDirectories(root);
            Files.writeString(root.resolve(DECODER_GENERATION_FILE), DECODER_GENERATION);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create persistent graph session " + root, e);
        }
        return new GraphSignalSessionStore(root, measurementSource, definitionSource, false);
    }

    /**
     * Reopen an existing durable session without loading sample payloads into heap.
     * Catalog metadata is seeded from the on-disk series index.
     */
    public static GraphSignalSessionStore openExisting(Path root) {
        LosslessGraphSignalStore graph = LosslessGraphSignalStore.openExisting(root);
        return wrapOpened(graph);
    }

    /**
     * Resume path: open the index whose sample total matches the last committed ledger batch.
     */
    public static GraphSignalSessionStore openExistingMatchingSamples(Path root, long ledgerSamples) {
        Path index = LosslessGraphSignalStore.indexMatchingSampleCount(root, ledgerSamples);
        if (index == null) {
            throw new IllegalStateException(
                    "No lossless index matches committed sample count " + ledgerSamples + " at " + root);
        }
        return wrapOpened(LosslessGraphSignalStore.openExisting(root, index));
    }

    public static boolean hasIncompleteFlag(Path root) {
        return root != null && Files.isRegularFile(root.resolve(INCOMPLETE_FLAG));
    }

    /** True when this store was written by the current decoder generation. */
    public static boolean hasCurrentDecoderGeneration(Path root) {
        if (root == null) {
            return false;
        }
        Path marker = root.resolve(DECODER_GENERATION_FILE);
        try {
            return Files.isRegularFile(marker) && DECODER_GENERATION.equals(Files.readString(marker).trim());
        } catch (IOException ex) {
            return false;
        }
    }

    private static GraphSignalSessionStore wrapOpened(LosslessGraphSignalStore graph) {
        GraphSignalSessionStore session = new GraphSignalSessionStore(
                graph.root(),
                graph.measurementSource(),
                graph.definitionSource(),
                graph,
                false
        );
        List<SignalCatalogEntry> entries = graph.catalogEntries();
        session.preview.seedCatalogMetadata(entries);
        return session;
    }

    public GraphSignalSessionStore(Path root, String measurementSource, String definitionSource) {
        this(root, measurementSource, definitionSource, false);
    }

    private GraphSignalSessionStore(Path root, String measurementSource, String definitionSource, boolean deleteRootOnClose) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        this.deleteRootOnClose = deleteRootOnClose;
        this.preview = new InMemorySignalCatalogStore(measurementSource, definitionSource, 1);
        this.graph = new LosslessGraphSignalStore(this.root, measurementSource, definitionSource);
    }

    private GraphSignalSessionStore(
            Path root,
            String measurementSource,
            String definitionSource,
            LosslessGraphSignalStore graph,
            boolean deleteRootOnClose
    ) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        this.deleteRootOnClose = deleteRootOnClose;
        this.preview = new InMemorySignalCatalogStore(measurementSource, definitionSource, 1);
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    @Override public void accept(List<CanonicalSignal> signals) {
        if (signals == null || signals.isEmpty()) return;
        graph.accept(signals);
        preview.accept(signals);
    }

    public GraphSeriesQuery graphQuery() { return graph; }
    public LosslessGraphSignalStore losslessStore() { return graph; }
    public Path root() { return root; }
    public boolean deleteRootOnClose() { return deleteRootOnClose; }

    /** Persist the on-disk series index without closing the store. */
    public void checkpoint() {
        graph.persistIndex();
    }

    /** Publish the current index as the SQLite-committed generation. */
    public void promoteCommittedIndex() {
        graph.promoteCommittedIndex();
    }

    /**
     * End a processing write-session: flush/close all open series streams and persist the index.
     * Artifacts remain on disk and queries continue to work; incremental accept() may reopen writers.
     */
    public void finishWriteSession() {
        graph.finishWriteSession();
    }

    /** Number of series with an open output stream / file channel. */
    public int activeWriterCount() {
        return graph.activeWriterCount();
    }

    @Override public SignalCatalog snapshot() { return preview.snapshot(); }
    @Override
    public void forEachSample(SignalCatalogKey key, java.util.function.Consumer<SignalSamplePoint> consumer) {
        graph.forEachSample(key, consumer);
    }

    @Override public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
        int safeLimit = Math.min(Math.max(1, limit), InMemorySignalCatalogStore.DEFAULT_QUERY_PAGE_LIMIT);
        return graph.querySamples(key, Math.max(0, offset), safeLimit);
    }
    @Override public int retainedSampleCount(SignalCatalogKey key) {
        return (int) Math.min(Integer.MAX_VALUE, graph.sampleCount(key));
    }
    @Override public long retainedWindowStartIndex(SignalCatalogKey key) { return 0L; }

    @Override
    public List<SignalSamplePoint> queryTimestampRange(
            SignalCatalogKey key,
            long startNanos,
            long endNanos,
            int maxSamples
    ) {
        return graph.queryRange(key, startNanos, endNanos, maxSamples);
    }

    @Override public void close() {
        graph.close();
        if (deleteRootOnClose) deleteTreeBestEffort(root);
    }

    private static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }
}
