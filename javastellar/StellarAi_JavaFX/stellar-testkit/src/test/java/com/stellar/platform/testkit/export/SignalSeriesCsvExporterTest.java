package com.stellar.platform.testkit.export;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalSeriesCsvExporterTest {

    @TempDir
    Path temp;

    @Test
    void exportsAllRetainedSamplesWithGlobalIndexesViaBoundedPages() throws Exception {
        int total = 23;
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 1_000);
        List<CanonicalSignal> batch = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            batch.add(new CanonicalSignal(
                    "EXPORT_SIG",
                    "MSG",
                    i * 1_000_000_000L + 123L,
                    0x7E8,
                    0,
                    i + 0.5,
                    (long) i,
                    "rpm",
                    null,
                    SourceReference.ofLine(i + 1)
            ));
        }
        store.accept(batch);
        SignalCatalogEntry entry = store.snapshot().entries().getFirst();

        Path out = temp.resolve("export.csv");
        long written = new SignalSeriesCsvExporter(5).export(store, entry, out);
        assertEquals(total, written);

        List<String> lines = Files.readAllLines(out);
        assertEquals(SignalSeriesCsvExporter.HEADER, lines.getFirst());
        assertEquals(total + 1, lines.size());
        assertTrue(lines.get(1).startsWith("EXPORT_SIG,0,123,"));
        assertTrue(lines.get(total).startsWith("EXPORT_SIG,22,"));
        assertTrue(lines.get(11).contains(",10,")); // global index 10
        assertEquals("0.000000123", SignalSeriesCsvExporter.secondsExact(123L));
    }
}
