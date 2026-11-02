package com.stellar.platform.processing.export;

import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingSignalCsvExporterTest {

    @TempDir
    Path temp;

    @Test
    void exportPagesInsteadOfLoadingTheSeries() throws Exception {
        SignalCatalogKey key = new SignalCatalogKey(
                "RPM", SignalSourceType.DBC_CAN, "E", 1L, "a.mf4", "a.dbc");
        AtomicInteger maxLimit = new AtomicInteger();
        SignalSeriesStore store = new SignalSeriesStore() {
            @Override
            public SignalCatalog snapshot() {
                return SignalCatalog.empty();
            }

            @Override
            public List<SignalSamplePoint> querySamples(SignalCatalogKey k, int offset, int limit) {
                maxLimit.accumulateAndGet(limit, Math::max);
                int from = Math.min(10_000, Math.max(0, offset));
                int to = Math.min(10_000, from + limit);
                return java.util.stream.IntStream.range(from, to)
                        .mapToObj(i -> new SignalSamplePoint(i * 1_000_000L, i, (long) i, null))
                        .toList();
            }

            @Override
            public int retainedSampleCount(SignalCatalogKey k) {
                return 10_000;
            }

            @Override
            public long retainedWindowStartIndex(SignalCatalogKey k) {
                return 0L;
            }
        };
        Path csv = temp.resolve("out.csv");
        long bytes = new StreamingSignalCsvExporter().export(store, List.of(key), csv, null);
        assertTrue(bytes > 0L);
        assertTrue(maxLimit.get() <= StreamingSignalCsvExporter.PAGE);
        String text = Files.readString(csv);
        assertTrue(text.startsWith("signal,timestamp_nanos,physical,raw"));
        assertFalse(text.contains("10000,"));
    }
}