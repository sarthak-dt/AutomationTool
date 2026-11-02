package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphSignalSessionStorePagingTest {
    @TempDir Path tempDir;

    @Test
    void pagesFullSignalHistoryFromDiskWhileHeapPreviewStaysTiny() {
        try (GraphSignalSessionStore store = new GraphSignalSessionStore(tempDir.resolve("session"), "run.mf4", "veh.dbc")) {
            List<CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 12_000; i++) {
                batch.add(new CanonicalSignal("Speed", "MSG", i, 0x123L, 0, i, (long) i, "km/h", null, SourceReference.ofLine(i + 1)));
            }
            store.accept(batch);
            SignalCatalogKey key = InMemorySignalCatalogStore.keyOf(batch.getFirst(), "run.mf4", "veh.dbc");

            assertEquals(12_000, store.retainedSampleCount(key));
            assertEquals(0L, store.retainedWindowStartIndex(key));
            var page = store.querySamples(key, 10_000, 500);
            assertEquals(500, page.size());
            assertEquals(10_000L, page.getFirst().timestampNanos());
            assertEquals(10_499L, page.getLast().timestampNanos());
        }
    }
}
