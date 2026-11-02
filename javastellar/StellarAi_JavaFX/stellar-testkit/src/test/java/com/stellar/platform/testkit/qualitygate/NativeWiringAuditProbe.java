package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfNativeMeasurementResolver;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime wiring audit: SPI ? MF4 decoder ? processing-core ? SignalCatalog.
 * Diagnostic only ? bounded counters, no per-sample dump of multi-GB files.
 */
public final class NativeWiringAuditProbe {

    private NativeWiringAuditProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: NativeWiringAuditProbe <mf4> <dbc>");
            System.exit(2);
        }
        Path mf4 = Path.of(args[0]);
        Path dbc = Path.of(args[1]);

        System.out.println("=== SPI CONTRACT ===");
        AutomotiveFileReader reader = ParserRegistry.load().findReader(mf4)
                .orElseThrow(() -> new IllegalStateException("no reader"));
        System.out.println("readerClass=" + reader.getClass().getName());
        System.out.println("implementsNativeSignalCapableReader=" + (reader instanceof NativeSignalCapableReader));
        System.out.println("formatKey=" + reader.formatKey());

        System.out.println("=== SCHEMA / DECODER (bounded decodeAll for small fixtures only) ===");
        var decoded = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
        long nativeSchemas = decoded.schemaResult().recordSchemas().stream()
                .filter(MdfNativeMeasurementResolver::emitsNativeMeasurements)
                .count();
        long canSchemas = decoded.schemaResult().recordSchemas().stream()
                .filter(MdfRecordSchema::emitsCanonicalCanFrames)
                .count();
        System.out.println("nativeMeasurementSchemas=" + nativeSchemas);
        System.out.println("canFrameSchemas=" + canSchemas);
        System.out.println("decoderNativeSignals=" + decoded.nativeSignals().size());
        System.out.println("decoderCanFrames=" + decoded.frames().size());
        if (!decoded.nativeSignals().isEmpty()) {
            var s0 = decoded.nativeSignals().getFirst();
            System.out.printf(Locale.ROOT,
                    "firstNative name=%s tsNanos=%d tsSeconds=%.12g phys=%s raw=%s%n",
                    s0.signalName(), s0.timestampNanos(), s0.timestampNanos() / 1e9,
                    s0.physicalValue(), s0.rawValue());
        }
        if (!decoded.frames().isEmpty()) {
            var f0 = decoded.frames().stream().filter(f -> f.canId() == 0x7E8L).findFirst()
                    .orElse(decoded.frames().getFirst());
            System.out.printf(Locale.ROOT,
                    "sampleFrame canId=0x%x tsNanos=%d tsSeconds=%.12g%n",
                    f0.canId(), f0.timestampNanos(), f0.timestampNanos() / 1e9);
        }

        System.out.println("=== PROCESSING-CORE FAN-IN ===");
        AtomicLong processingNative = new AtomicLong();
        AtomicLong processingDbc = new AtomicLong();
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(), dbc.getFileName().toString());
        var database = new DbcParser().parse(dbc);
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                mf4,
                database,
                ReaderContext.ofBatchSize(256),
                batch -> {
                    catalog.accept(batch);
                    for (var s : batch) {
                        if (InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE.equals(s.messageName())) {
                            processingNative.incrementAndGet();
                        } else {
                            processingDbc.incrementAndGet();
                        }
                    }
                }
        );

        long catalogNative = catalog.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE).count();
        long catalogDbc = catalog.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN).count();
        long catalogNativeSamples = catalog.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .mapToLong(SignalCatalogEntry::sampleCount)
                .sum();

        System.out.println("processingNativeReceived=" + processingNative.get());
        System.out.println("processingDbcReceived=" + processingDbc.get());
        System.out.println("stats.nativeSignalsReceived=" + result.statistics().nativeSignalsReceived());
        System.out.println("stats.dbcSignalsReceived=" + result.statistics().dbcSignalsReceived());
        System.out.println("stats.signalsEmitted=" + result.statistics().signalsEmitted());
        System.out.println("outcome=" + result.outcome() + " success=" + result.completedSuccessfully());
        System.out.println("catalogMf4NativeEntries=" + catalogNative);
        System.out.println("catalogDbcCanEntries=" + catalogDbc);
        System.out.println("catalogMf4NativeSampleSum=" + catalogNativeSamples);

        System.out.println("=== CATALOG ENTRIES (name/source/samples/firstTsSeconds) ===");
        for (SignalCatalogEntry e : catalog.snapshot().sortedByName().entries()) {
            double firstSec = e.firstTimestampNanos() == null
                    ? Double.NaN
                    : e.firstTimestampNanos() / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "%s | %s | samples=%d | firstTsSeconds=%.8g%n",
                    e.signalName(), e.sourceType(), e.sampleCount(), firstSec);
        }
    }
}
