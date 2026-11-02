package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.mf4.Mf4FileReader;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStatistics;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Offline one-MF4 + one-DBC Signal Catalog gate. Not run by Surefire.
 */
public final class SignalCatalogGateProbe {

    public static void main(String[] args) throws Exception {
        Path mf4 = Path.of(args.length > 0 ? args[0]
                : "C:/Users/ADMIN/Desktop/DriveTech/OBD2 Data_Lavasa_0002260_20240305_102230.mf4");
        Path dbcPath = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");

        System.out.println("=== SignalCatalogGateProbe ===");
        System.out.println("MF4=" + mf4);
        System.out.println("DBC=" + dbcPath);

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(512));
        long dg = decoded.schemaResult().recordSchemas().stream()
                .map(MdfRecordSchema::dataGroupAddress).distinct().count();
        long cg = decoded.schemaResult().recordSchemas().size();
        long cn = decoded.schemaResult().recordSchemas().stream()
                .mapToLong(s -> s.channelFields().size()).sum();
        long nativeCg = decoded.schemaResult().recordSchemas().stream()
                .filter(s -> !s.emitsCanonicalCanFrames()).count();
        System.out.println("MF4 frames=" + decoded.frames().size()
                + " DG~=" + dg + " CG=" + cg + " CN_fields=" + cn
                + " nonCanCg=" + nativeCg);

        DbcDatabase db = new DbcParser().parse(dbcPath);
        long mux = db.messages().stream().flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() != DbcMultiplexRole.NONE).count();
        long signals = db.messages().stream().mapToLong(m -> m.signals().size()).sum();
        System.out.println("DBC messages=" + db.messages().size()
                + " signals=" + signals + " muxSignals=" + mux);

        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(),
                dbcPath.getFileName().toString(),
                50_000
        );
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                ParserRegistry.load().findReader(mf4).orElseThrow(),
                mf4,
                db,
                ReaderContext.ofBatchSize(512),
                store::accept
        );
        ProcessingStatistics stats = result.statistics();
        SignalCatalog catalog = store.snapshot();

        System.out.println("pipeline matched=" + stats.dbcMatchedFrames()
                + " unmatched=" + stats.dbcUnmatchedFrames()
                + " signalsEmitted=" + stats.signalsEmitted()
                + " outcome=" + result.outcome());
        System.out.println("catalogEntries=" + catalog.size()
                + " dbcCan=" + catalog.dbcCanSignalCount()
                + " mf4Native=" + catalog.nativeMf4SignalCount());

        System.out.println("\n=== Search: speed / odo / ground / distance ===");
        for (String q : List.of("speed", "odo", "ground", "distance", "Vehicle", "Kilometer")) {
            List<SignalCatalogEntry> hits = catalog.search(q);
            System.out.println("q='" + q + "' hits=" + hits.size());
            hits.stream().limit(8).forEach(e -> System.out.println("  "
                    + e.signalName() + " [" + e.sourceType() + "] samples=" + e.sampleCount()
                    + " unit=" + e.unit() + " msg=" + e.messageName()));
        }

        System.out.println("\n=== Top 25 catalog by sample count ===");
        catalog.entries().stream()
                .sorted(Comparator.comparingLong(SignalCatalogEntry::sampleCount).reversed())
                .limit(25)
                .forEach(e -> System.out.println(
                        String.format(Locale.ROOT, "%6d  %-40s %-12s %s",
                                e.sampleCount(), e.signalName(), e.sourceType(), e.unit())));

        System.out.println("\n=== Value spot-check (first retained sample) ===");
        for (String name : List.of(
                "S01PID05_EngineCoolantTemp",
                "S01PID0D_VehicleSpeed",
                "S01PIDA6_Odometer",
                "SPEED_OVER_GROUND",
                "GROUND_DISTANCE"
        )) {
            catalog.entries().stream()
                    .filter(e -> e.signalName().equals(name))
                    .findFirst()
                    .ifPresentOrElse(e -> {
                        var samples = store.querySamples(e.key(), 0, 3);
                        System.out.println(name + " count=" + e.sampleCount()
                                + " min=" + e.minPhysical() + " max=" + e.maxPhysical()
                                + " firstSamples=" + samples);
                    }, () -> System.out.println(name + " MISSING_FROM_CATALOG"));
        }

        System.out.println("reader=" + Mf4FileReader.class.getSimpleName());
    }
}
