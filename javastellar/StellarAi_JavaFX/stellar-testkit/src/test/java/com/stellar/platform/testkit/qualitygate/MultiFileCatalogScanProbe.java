package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Scan many MF4+DBC pairs for catalog anomalies (only-S, zero signals, tiny catalogs).
 */
public final class MultiFileCatalogScanProbe {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "C:/Users/ADMIN/Desktop/DriveTech/KALPESH");
        Path defaultDbc = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");

        List<Path> mf4s = new ArrayList<>();
        try (var walk = Files.walk(root, 2)) {
            walk.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".mf4") || n.endsWith(".MF4".toLowerCase(Locale.ROOT));
            }).forEach(mf4s::add);
        }
        mf4s.sort(Comparator.comparing(p -> p.getFileName().toString()));
        System.out.println("Scanning " + mf4s.size() + " MF4 under " + root);
        System.out.println("DBC=" + defaultDbc);

        DbcDatabase db = new DbcParser().parse(defaultDbc);
        var pipeline = MeasurementDecodePipeline.createDefault();
        var registry = ParserRegistry.load();

        for (Path mf4 : mf4s) {
            try {
                if (registry.findReader(mf4).isEmpty()) {
                    System.out.println("SKIP no reader: " + mf4.getFileName());
                    continue;
                }
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(
                        mf4.getFileName().toString(), defaultDbc.getFileName().toString(), 5_000);
                ProcessingResult result = pipeline.process(
                        registry.findReader(mf4).orElseThrow(),
                        mf4,
                        db,
                        ReaderContext.ofBatchSize(512),
                        store::accept
                );
                SignalCatalog cat = store.snapshot();
                long dbcEntries = cat.dbcCanSignalCount();
                long nativeEntries = cat.nativeMf4SignalCount();
                boolean onlyS = dbcEntries == 1 && cat.entries().stream()
                        .anyMatch(e -> "S".equals(e.signalName()) && e.sourceType().name().equals("DBC_CAN"));
                String dbcNames = cat.entries().stream()
                        .filter(e -> e.sourceType().name().equals("DBC_CAN"))
                        .map(e -> e.signalName() + ":" + e.sampleCount())
                        .collect(Collectors.joining(", "));
                System.out.println(String.format(Locale.ROOT,
                        "%s | frames=%d matched=%d unmatched=%d signals=%d catalog=%d dbc=%d native=%d onlyS=%s | %s",
                        mf4.getFileName(),
                        result.statistics().sourceFramesRead(),
                        result.statistics().dbcMatchedFrames(),
                        result.statistics().dbcUnmatchedFrames(),
                        result.statistics().signalsEmitted(),
                        cat.size(),
                        dbcEntries,
                        nativeEntries,
                        onlyS,
                        dbcNames.length() > 200 ? dbcNames.substring(0, 200) + "…" : dbcNames
                ));
            } catch (Exception e) {
                System.out.println("FAIL " + mf4.getFileName() + " :: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
    }
}
