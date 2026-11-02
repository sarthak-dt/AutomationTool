package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeSignalCatalogStoreTest {

    @Test
    void namespacesSameDisplayFilenameWithStableProjectIdsWithoutCopyingSamples() {
        InMemorySignalCatalogStore a = store("same.mf4", "same.dbc", 1, 10L, 1.0);
        InMemorySignalCatalogStore b = store("same.mf4", "same.dbc", 1, 20L, 2.0);

        CompositeSignalCatalogStore composite = CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(a, "source-A", "def-A", "same.mf4", "same.dbc"),
                new CompositeSignalCatalogStore.Member(b, "source-B", "def-A", "same.mf4", "same.dbc")
        ), "2 Sources", "same.dbc");

        assertEquals(2, composite.physicalSignalCount());
        List<SignalCatalogEntry> entries = composite.snapshot().entries();
        assertNotEquals(entries.get(0).key().sourceFile(), entries.get(1).key().sourceFile());
        assertEquals("same.mf4", entries.get(0).sourceFile());
        assertEquals("same.mf4", entries.get(1).sourceFile());
        assertEquals(1, composite.querySamples(entries.get(0).key(), 0, 10).size());
        assertEquals(1, composite.querySamples(entries.get(1).key(), 0, 10).size());
    }

    @Test
    void delegatesQueriesToOriginatingBoundedStore() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 2);
        store.accept(List.of(
                signal(1, 10, 10.0), signal(1, 20, 20.0), signal(1, 30, 30.0)
        ));
        CompositeSignalCatalogStore composite = CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(store, "source-A", "def-A", "run.mf4", "veh.dbc")
        ), "run.mf4", "veh.dbc");
        SignalCatalogKey key = composite.snapshot().entries().getFirst().key();

        assertEquals(2, composite.retainedSampleCount(key));
        assertEquals(1, composite.retainedWindowStartIndex(key));
        assertEquals(List.of(20L, 30L), composite.querySamples(key, 0, 10).stream()
                .map(p -> p.timestampNanos()).toList());
    }

    @Test
    void duplicatePhysicalIdentityFailsClosedInsteadOfSilentlyMerging() {
        InMemorySignalCatalogStore a = store("a.mf4", "a.dbc", 1, 10L, 1.0);
        InMemorySignalCatalogStore b = store("b.mf4", "b.dbc", 1, 20L, 2.0);

        assertThrows(IllegalArgumentException.class, () -> CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(a, "same-source", "same-def", "a.mf4", "a.dbc"),
                new CompositeSignalCatalogStore.Member(b, "same-source", "same-def", "b.mf4", "b.dbc")
        ), "", ""));
    }

    private static InMemorySignalCatalogStore store(
            String source, String dbc, int channel, long ts, double value) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, dbc, 10);
        store.accept(List.of(signal(channel, ts, value)));
        return store;
    }

    private static CanonicalSignal signal(int channel, long ts, double value) {
        return new CanonicalSignal(
                "VehicleSpeed", "VehicleState", ts, 0x123, channel, value, null, "km/h", null,
                SourceReference.ofLine(1));
    }
}
