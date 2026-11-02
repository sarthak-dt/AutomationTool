package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One forward read of IN_Vehicle_speed across the isolated 73-file store.
 * The freeze read the same bytes again for every page. This must stay a single pass.
 */
class VehicleSpeedPassProbeTest {

    @Test
    void onePassOverVehicleSpeedStaysBounded() throws Exception {
        Path storeRoot = Path.of(System.getenv("TEMP"),
                "stellar-accuracy", "m2-73-home", "analysis-storage", "StellarAIAnalysis",
                "projects", "1001", "signal-store");
        Assumptions.assumeTrue(Files.isDirectory(storeRoot), "isolated store missing");
        List<Path> members = Files.list(storeRoot)
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve("ch-0").resolve("_none_"))
                .filter(dir -> Files.isRegularFile(dir.resolve("series-index.v1")))
                .sorted()
                .toList();
        Assumptions.assumeFalse(members.isEmpty());
        long started = System.nanoTime();
        AtomicLong samples = new AtomicLong();
        int files = 0;
        for (Path member : members) {
            try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(member)) {
                SignalCatalogKey speed = null;
                for (SignalCatalogEntry entry : store.catalogEntries()) {
                    if ("IN_Vehicle_speed".equals(entry.key().signalName())) {
                        speed = entry.key();
                        break;
                    }
                }
                if (speed == null) {
                    continue;
                }
                files++;
                store.forEachSample(speed, sample -> samples.incrementAndGet());
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("SPEED_PASS_FILES=" + files);
        System.out.println("SPEED_PASS_SAMPLES=" + samples.get());
        System.out.println("SPEED_PASS_MS=" + elapsedMs);
        assertTrue(files >= 70, "files " + files);
        assertTrue(samples.get() > 1_000, "samples " + samples.get());
        assertTrue(elapsedMs < 30_000, "one pass took " + elapsedMs + " ms");
    }
}
