package com.stellar.platform.processing.nft;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.align.AlignedSignalWorkspaceQuery;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.export.StreamingSignalCsvExporter;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-nft")
class ReleaseNftExportSoakTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void csvExportCancelGraphSoakAndProjectSwitch() throws Exception {
        int n = 1_200_000;
        Path a = temp.resolve("projA");
        Path b = temp.resolve("projB");
        SignalCatalogKey keyA = write(a, "Speed", n);
        SignalCatalogKey keyB = write(b, "Rpm", 200_000);

        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(a)) {
            Path csv = temp.resolve("full.csv");
            long heap0 = heap();
            long t0 = System.nanoTime();
            long bytes = new StreamingSignalCsvExporter().export(read, List.of(keyA), csv, new AtomicBoolean());
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            double mbps = ms <= 0 ? 0 : (bytes / 1024.0 / 1024.0) / (ms / 1000.0);
            System.out.println("PHASE9C_EXPORT dataset=SYNTHETIC_STORE n=" + n
                    + " durationMs=" + ms + " bytes=" + bytes + " mbps=" + String.format(java.util.Locale.ROOT, "%.2f", mbps)
                    + " heapBefore=" + heap0 + " heapAfter=" + heap());
            assertTrue(bytes > n, "export must write more than one byte per sample");

            Path partial = temp.resolve("partial.csv");
            AtomicBoolean cancel = new AtomicBoolean();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancel.set(true);
            }, "nft-export-cancel");
            canceller.start();
            new StreamingSignalCsvExporter().export(read, List.of(keyA), partial, cancel);
            canceller.join(5_000);
            long partialBytes = Files.size(partial);
            System.out.println("PHASE9C_EXPORT_CANCEL partialBytes=" + partialBytes + " fullBytes=" + bytes
                    + " cancelled=" + cancel.get());
            assertTrue(cancel.get(), "cancel flag must trip after progress");
            assertTrue(partialBytes < bytes, "partial export must not equal successful final export");
        }

        int threads0 = threads();
        long heap0 = heap();
        GraphViewportEngine engineA;
        GraphViewportEngine engineB;
        try (GraphSignalSessionStore sa = GraphSignalSessionStore.openExisting(a);
             GraphSignalSessionStore sb = GraphSignalSessionStore.openExisting(b)) {
            engineA = new GraphViewportEngine(sa.graphQuery());
            engineB = new GraphViewportEngine(sb.graphQuery());
            for (int i = 0; i < 30; i++) {
                AlignedSignalWorkspaceQuery.query(sa, AlignedSignalWorkspaceQuery.Request.first(List.of(keyA), 500));
                engineA.render(new GraphViewportRequest(List.of(keyA), 0, n * 1_000_000L, 400 + (i % 5) * 80));
                AlignedSignalWorkspaceQuery.query(sb, AlignedSignalWorkspaceQuery.Request.first(List.of(keyB), 500));
                engineB.render(new GraphViewportRequest(List.of(keyB), 0, 200_000L * 1_000_000L, 400));
            }
        }
        long heap1 = heap();
        int threads1 = threads();
        System.out.println("PHASE9C_SOAK projectSwitchCycles=30 heap0=" + heap0 + " heap1=" + heap1
                + " threads0=" + threads0 + " threads1=" + threads1
                + " queryKind=DATA_QUERY");
        assertTrue(threads1 <= threads0 + 8, "thread count must not grow unbounded");
    }

    private static SignalCatalogKey write(Path root, String name, int total) {
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, name + ".mf4", "syn.dbc")) {
            List<CanonicalSignal> batch = new ArrayList<>(8192);
            for (int i = 0; i < total; i++) {
                batch.add(new CanonicalSignal(
                        name, "Veh", (i + 1L) * 1_000_000L, 0x100L, 1, i, (long) i, "u", null,
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
                    new CanonicalSignal(name, "Veh", 1L, 0x100L, 1, 0, 0L, "u", null,
                            SourceReference.ofLine(1)),
                    name + ".mf4", "syn.dbc");
        }
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static int threads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }
}
