package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.testkit.export.SignalSeriesCsvExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Diagnostic-only probe: dump samples around global indexes 4998-5001 for a named signal
 * (default {@code GEOID_SEPARATION}) to verify zero-based index alignment.
 * <p>
 * Does not hard-code expected values. Run:
 * {@code mvn -pl stellar-testkit exec:java -Dexec.mainClass=com.stellar.platform.testkit.qualitygate.GeoidIndexWindowProbe}
 */
public final class GeoidIndexWindowProbe {

    private static final Path DEFAULT_MF4 = Path.of(
            "C:/Users/ADMIN/Desktop/DriveTech/OBD2 Data_Lavasa_0002260_20240305_102230.mf4");
    private static final Path DEFAULT_DBC = Path.of(
            "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");

    private GeoidIndexWindowProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path mf4 = args.length > 0 ? Path.of(args[0]) : DEFAULT_MF4;
        Path dbc = args.length > 1 ? Path.of(args[1]) : DEFAULT_DBC;
        String signal = args.length > 2 ? args[2] : "GEOID_SEPARATION";
        if (!Files.isRegularFile(mf4) || !Files.isRegularFile(dbc)) {
            System.out.println("SKIP: MF4/DBC not found");
            System.out.println("mf4=" + mf4);
            System.out.println("dbc=" + dbc);
            return;
        }
        dumpWindow(mf4, dbc, signal, 4998, 5001);
    }

    static void dumpWindow(Path mf4, Path dbc, String signalName, int fromInclusive, int toInclusive)
            throws Exception {
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(),
                dbc.getFileName().toString()
        );
        MeasurementDecodePipeline.createDefault().process(
                mf4,
                new DbcParser().parse(dbc),
                ReaderContext.ofBatchSize(4096),
                catalog::accept
        );

        SignalCatalogEntry entry = catalog.snapshot().entries().stream()
                .filter(e -> signalName.equals(e.signalName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Signal not found: " + signalName));

        System.out.println("signal=" + entry.signalName());
        System.out.println("totalSamples=" + entry.sampleCount());
        System.out.println("retained=" + catalog.retainedSampleCount(entry.key()));
        System.out.println("windowStart=" + catalog.retainedWindowStartIndex(entry.key()));

        long windowStart = catalog.retainedWindowStartIndex(entry.key());
        int localFrom = (int) Math.max(0, fromInclusive - windowStart);
        int count = toInclusive - fromInclusive + 1;
        List<SignalSamplePoint> page = catalog.querySamples(entry.key(), localFrom, count);

        System.out.println("global_index\tcanonical_timestamp_nanos\tdisplay_timestamp\tphysical_value");
        for (int i = 0; i < page.size(); i++) {
            long global = windowStart + localFrom + i;
            SignalSamplePoint p = page.get(i);
            System.out.println(global
                    + "\t" + p.timestampNanos()
                    + "\t" + SignalSeriesCsvExporter.secondsExact(p.timestampNanos())
                    + "\t" + Double.toString(p.physicalValue()));
        }
    }
}
