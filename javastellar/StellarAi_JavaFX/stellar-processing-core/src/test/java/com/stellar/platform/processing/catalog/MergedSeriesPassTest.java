package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MergedSeriesPassTest {

    @Test
    void fullPassVisitsEachMemberOnceWithoutRestartingTheMerge() {
        InMemorySignalCatalogStore fileA = store("a.dat", List.of(
                sample("IN_Vehicle_speed", 10L, 1.0),
                sample("IN_Vehicle_speed", 30L, 3.0)));
        InMemorySignalCatalogStore fileB = store("b.dat", List.of(
                sample("IN_Vehicle_speed", 20L, 2.0),
                sample("IN_Vehicle_speed", 40L, 4.0)));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(fileA, "src-a", "def", "a.dat", "def"),
                new ProjectMergedSignalStore.Member(fileB, "src-b", "def", "b.dat", "def")
        ), "files", "def");
        SignalCatalogKey logical = merged.snapshot().entries().getFirst().key();
        AtomicInteger seen = new AtomicInteger();
        merged.forEachSample(logical, sample -> seen.incrementAndGet());
        assertEquals(4, seen.get());
        List<SignalSamplePoint> page = merged.querySamples(logical, 0, 4);
        assertEquals(4, page.size());
    }

    private static InMemorySignalCatalogStore store(String source, List<CanonicalSignal> signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, "none", 32);
        store.accept(signals);
        return store;
    }

    private static CanonicalSignal sample(String name, long ts, double value) {
        return new CanonicalSignal(
                name, "Vehicle", ts, 0x100L, 1, value, (long) value, "km/h", null, SourceReference.ofLine(1));
    }
}
