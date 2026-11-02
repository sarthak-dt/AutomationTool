package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LosslessGraphSignalStorePersistenceTest {

    @TempDir Path temp;

    @Test
    void checkpointAllowsOpenExistingWithoutReloadingSamplesIntoHeap() {
        Path root = temp.resolve("store");
        SignalCatalogKey key;
        try (LosslessGraphSignalStore write = new LosslessGraphSignalStore(root, "run.mf4", "veh.dbc", 4)) {
            List<CanonicalSignal> batch = List.of(
                    signal("Speed", 1, 10L, 11.0),
                    signal("Speed", 1, 20L, 12.0),
                    signal("Speed", 1, 30L, 13.0)
            );
            write.accept(batch);
            write.persistIndex();
            key = InMemorySignalCatalogStore.keyOf(batch.getFirst(), "run.mf4", "veh.dbc");
            assertEquals(3L, write.sampleCount(key));
        }

        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            assertEquals(1, read.keys().size());
            assertEquals(3L, read.sampleCount(key));
            List<SignalSamplePoint> page = read.querySamples(key, 0, 10);
            assertEquals(3, page.size());
            assertEquals(11.0, page.getFirst().physicalValue());
            assertEquals(13.0, page.getLast().physicalValue());
            assertEquals(1, read.catalogEntries().size());
            assertEquals("Speed", read.catalogEntries().getFirst().signalName());
        }
    }

    @Test
    void sessionOpenExistingSeedsPreviewCatalogMetadataOnly() throws Exception {
        Path root = temp.resolve("session");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "run.mf4", "veh.dbc")) {
            write.accept(List.of(signal("RPM", 2, 5L, 800.0), signal("RPM", 2, 15L, 900.0)));
            write.checkpoint();
            assertEquals(1, write.snapshot().entries().size());
            assertFalse(write.deleteRootOnClose());
        }

        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
            assertEquals(1, read.snapshot().entries().size());
            assertEquals(2, read.retainedSampleCount(read.snapshot().entries().getFirst().key()));
            assertEquals(2, read.querySamples(read.snapshot().entries().getFirst().key(), 0, 10).size());
            assertTrue(Files.isRegularFile(root.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME)));
        }
    }

    @Test
    void closedStoreReadsReturnEmptyWithoutThrowing() throws Exception {
        Path root = temp.resolve("closed");
        SignalCatalogKey key;
        LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, "run.mf4", "veh.dbc", 4);
        try {
            List<CanonicalSignal> batch = List.of(signal("Speed", 1, 10L, 11.0));
            store.accept(batch);
            store.finishWriteSession();
            key = InMemorySignalCatalogStore.keyOf(batch.getFirst(), "run.mf4", "veh.dbc");
            assertEquals(1L, store.sampleCount(key));
        } finally {
            store.close();
        }
        assertEquals(0L, store.sampleCount(key));
        assertTrue(store.querySamples(key, 0, 10).isEmpty());
        assertTrue(store.queryRange(key, 0L, Long.MAX_VALUE).isEmpty());
        assertEquals("", store.unit(key));
    }

    @Test
    void unindexedTrailingBytesAreTruncatedAndIndexMatchingPicksCommittedGeneration() throws Exception {
        Path root = temp.resolve("trunc");
        SignalCatalogKey key;
        try (LosslessGraphSignalStore write = new LosslessGraphSignalStore(root, "run.mf4", "veh.dbc", 16)) {
            write.accept(List.of(signal("Speed", 1, 10L, 11.0), signal("Speed", 1, 20L, 12.0)));
            write.persistIndex();
            write.promoteCommittedIndex();
            key = write.keys().getFirst();
            write.accept(List.of(signal("Speed", 1, 30L, 13.0)));
            write.persistIndex();
            assertEquals(3L, write.sampleCount(key));
        }
        Path committed = root.resolve(LosslessGraphSignalStore.COMMITTED_INDEX_FILE_NAME);
        assertEquals(2L, LosslessGraphSignalStore.peekTotalSamples(committed));
        assertEquals(committed, LosslessGraphSignalStore.indexMatchingSampleCount(root, 2L));
        try (LosslessGraphSignalStore resume = LosslessGraphSignalStore.openExisting(root, committed)) {
            assertEquals(2L, resume.sampleCount(key));
            resume.accept(List.of(signal("Speed", 1, 30L, 13.0)));
            resume.persistIndex();
            assertEquals(3L, resume.sampleCount(key));
            List<SignalSamplePoint> page = resume.querySamples(key, 0, 10);
            assertEquals(3, page.size());
            assertEquals(11.0, page.get(0).physicalValue());
            assertEquals(12.0, page.get(1).physicalValue());
            assertEquals(13.0, page.get(2).physicalValue());
        }
    }

    private static CanonicalSignal signal(String name, int channel, long ts, double value) {
        return new CanonicalSignal(name, "Engine", ts, 0x100L, channel, value, null, "rpm", null,
                SourceReference.ofLine(1));
    }
}
