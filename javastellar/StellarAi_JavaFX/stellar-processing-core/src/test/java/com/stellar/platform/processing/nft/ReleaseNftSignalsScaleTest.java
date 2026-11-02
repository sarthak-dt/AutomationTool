package com.stellar.platform.processing.nft;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.align.AlignedSignalWorkspaceQuery;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SYNTHETIC STORE — not MF4 parser throughput. */
@Tag("release-nft")
class ReleaseNftSignalsScaleTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void pagingSeekSoakAndGraphQueryStayBounded() throws Exception {
        int[] sizes = {1_000_000, 10_000_000};
        for (int total : sizes) {
            if (Runtime.getRuntime().maxMemory() < 512L * 1024 * 1024 && total > 1_000_000) {
                System.out.println("PHASE9C_SIGNALS n=" + total + " NOT_TESTED reason=heap");
                continue;
            }
            Path root = temp.resolve("s" + total);
            SignalCatalogKey key = writeStore(root, "Speed", total);
            try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
                long heap0 = heap();
                long t0 = System.nanoTime();
                var first = AlignedSignalWorkspaceQuery.query(
                        read, AlignedSignalWorkspaceQuery.Request.first(List.of(key), 500));
                long page500 = (System.nanoTime() - t0) / 1_000_000L;
                assertEquals(500, first.rows().size());
                assertTrue(first.samplesRead() <= 1000, "samplesRead=" + first.samplesRead());

                t0 = System.nanoTime();
                AlignedSignalWorkspaceQuery.query(read, AlignedSignalWorkspaceQuery.Request.first(List.of(key), 1000));
                long page1000 = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                AlignedSignalWorkspaceQuery.query(read, AlignedSignalWorkspaceQuery.Request.first(List.of(key), 5000));
                long page5000 = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                AlignedSignalWorkspaceQuery.query(
                        read, AlignedSignalWorkspaceQuery.Request.atIndex(List.of(key), 500, total / 2));
                long midIndex = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                AlignedSignalWorkspaceQuery.query(
                        read, AlignedSignalWorkspaceQuery.Request.atTimestamp(List.of(key), 500, (total / 2L) * 1_000_000L));
                long midTime = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                AlignedSignalWorkspaceQuery.query(
                        read, AlignedSignalWorkspaceQuery.Request.atIndex(List.of(key), 500, Math.max(0, total - 500)));
                long endIndex = (System.nanoTime() - t0) / 1_000_000L;

                long soakHeap0 = heap();
                int threads0 = ManagementFactory.getThreadMXBean().getThreadCount();
                for (int i = 0; i < 200; i++) {
                    AlignedSignalWorkspaceQuery.query(read, AlignedSignalWorkspaceQuery.Request.first(List.of(key), 500));
                    AlignedSignalWorkspaceQuery.query(
                            read, AlignedSignalWorkspaceQuery.Request.atIndex(List.of(key), 500, (i * 1000L) % total));
                }
                long soakHeap1 = heap();
                int threads1 = ManagementFactory.getThreadMXBean().getThreadCount();

                GraphViewportEngine engine = new GraphViewportEngine(read.graphQuery());
                t0 = System.nanoTime();
                engine.render(new GraphViewportRequest(List.of(key), 0, total * 1_000_000L, 800));
                long graph1 = (System.nanoTime() - t0) / 1_000_000L;
                t0 = System.nanoTime();
                engine.render(new GraphViewportRequest(List.of(key), 0, total * 1_000_000L, 800));
                long graph2 = (System.nanoTime() - t0) / 1_000_000L;
                var stats = engine.queryCache().stats();

                System.out.println("PHASE9C_SIGNALS dataset=SYNTHETIC_STORE n=" + total
                        + " page500Ms=" + page500 + " page1000Ms=" + page1000 + " page5000Ms=" + page5000
                        + " midIndexMs=" + midIndex + " midTimeMs=" + midTime + " endIndexMs=" + endIndex
                        + " graphFirstMs=" + graph1 + " graphSecondMs=" + graph2
                        + " cacheHits=" + stats.hits() + " cacheMisses=" + stats.misses()
                        + " heapBefore=" + heap0 + " heapAfter=" + heap()
                        + " soakHeap0=" + soakHeap0 + " soakHeap1=" + soakHeap1
                        + " threads0=" + threads0 + " threads1=" + threads1
                        + " queryKind=DATA_QUERY");
                assertTrue(graph2 <= graph1 * 5 + 50, "second graph query should not explode");
            }
        }

        Path multi = temp.resolve("multi20");
        List<SignalCatalogKey> keys = writeMany(multi, 20, 50_000);
        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(multi)) {
            long t0 = System.nanoTime();
            var page = AlignedSignalWorkspaceQuery.query(
                    read, AlignedSignalWorkspaceQuery.Request.first(keys, 500));
            long alignMs = (System.nanoTime() - t0) / 1_000_000L;
            GraphViewportEngine engine = new GraphViewportEngine(read.graphQuery());
            t0 = System.nanoTime();
            var rendered = engine.render(new GraphViewportRequest(keys, 0, 50_000L * 1_000_000L, 400));
            long gMs = (System.nanoTime() - t0) / 1_000_000L;
            int points = rendered.stream().mapToInt(s -> s.buckets().size()).sum();
            System.out.println("PHASE9C_GRAPH dataset=SYNTHETIC_STORE signals=20 samplesEach=50000"
                    + " alignPageMs=" + alignMs + " graphQueryMs=" + gMs + " lodBuckets=" + points
                    + " rows=" + page.rows().size() + " queryKind=DATA_QUERY");
            assertEquals(20, rendered.size());
            assertTrue(points < 20 * 400 * 4, "must not emit millions of raw nodes");
        }
    }

    private static SignalCatalogKey writeStore(Path root, String name, int total) {
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "syn.mf4", "syn.dbc")) {
            List<CanonicalSignal> batch = new ArrayList<>(8192);
            for (int i = 0; i < total; i++) {
                batch.add(new CanonicalSignal(
                        name, "Veh", (i + 1L) * 1_000_000L, 0x100L, 1, i, (long) i, "km/h", null,
                        SourceReference.ofLine(1)));
                if (batch.size() == 8192) {
                    write.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                write.accept(batch);
            }
            write.checkpoint();
            return InMemorySignalCatalogStore.keyOf(
                    new CanonicalSignal(name, "Veh", 1L, 0x100L, 1, 0, 0L, "km/h", null,
                            SourceReference.ofLine(1)),
                    "syn.mf4", "syn.dbc");
        }
    }

    private static List<SignalCatalogKey> writeMany(Path root, int signals, int each) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.mf4", "m.dbc")) {
            for (int s = 0; s < signals; s++) {
                String name = "Sig" + s;
                List<CanonicalSignal> batch = new ArrayList<>(4096);
                for (int i = 0; i < each; i++) {
                    batch.add(new CanonicalSignal(
                            name, "Msg", (i + 1L) * 1_000_000L, 0x200L + s, 1, i, (long) i, "V", null,
                            SourceReference.ofLine(1)));
                    if (batch.size() == 4096) {
                        write.accept(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    write.accept(batch);
                }
                keys.add(InMemorySignalCatalogStore.keyOf(
                        new CanonicalSignal(name, "Msg", 1L, 0x200L + s, 1, 0, 0L, "V", null,
                                SourceReference.ofLine(1)),
                        "m.mf4", "m.dbc"));
            }
            write.checkpoint();
        }
        return keys;
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
