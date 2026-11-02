package com.stellar.platform.processing.align;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class AlignedSignalWorkspaceLargeStoreTest {

    @TempDir
    Path temp;

    @Test
    void millionsOfDiskSamplesStayBoundedOnFirstPageAndSeek() {
        Path root = temp.resolve("millions");
        SignalCatalogKey key;
        int total = 1_200_000;
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "big.mf4", "veh.dbc")) {
            List<CanonicalSignal> batch = new ArrayList<>(8_192);
            for (int i = 0; i < total; i++) {
                batch.add(new CanonicalSignal(
                        "Speed", "Veh", (i + 1L) * 1_000_000L, 0x100L, 1, i, (long) i, "km/h", null,
                        SourceReference.ofLine(1)));
                if (batch.size() == 8_192) {
                    write.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                write.accept(batch);
            }
            write.checkpoint();
            key = InMemorySignalCatalogStore.keyOf(
                    new CanonicalSignal("Speed", "Veh", 1L, 0x100L, 1, 0, 0L, "km/h", null,
                            SourceReference.ofLine(1)),
                    "big.mf4", "veh.dbc");
        }

        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
            List<SignalCatalogKey> keys = List.of(key);
            AlignedSignalWorkspaceQuery.Result first = AlignedSignalWorkspaceQuery.query(
                    read, AlignedSignalWorkspaceQuery.Request.first(keys, 500));
            assertEquals(500, first.rows().size());
            assertTrue(first.samplesRead() <= 500 * 2);
            AlignedSignalWorkspaceQuery.Result atIndex = AlignedSignalWorkspaceQuery.query(
                    read, AlignedSignalWorkspaceQuery.Request.atIndex(keys, 500, 900_000));
            assertEquals(500, atIndex.rows().size());
            AlignedSignalWorkspaceQuery.Result atTime = AlignedSignalWorkspaceQuery.query(
                    read, AlignedSignalWorkspaceQuery.Request.atTimestamp(keys, 500, 600_000L * 1_000_000L));
            assertEquals(500, atTime.rows().size());
            assertTrue(atTime.rows().getFirst().timestampNanos() >= 600_000L * 1_000_000L);
        }
    }
}
