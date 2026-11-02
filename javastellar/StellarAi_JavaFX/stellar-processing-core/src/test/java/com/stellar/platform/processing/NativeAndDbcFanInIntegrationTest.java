package com.stellar.platform.processing;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAndDbcFanInIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void canFramePlusNativeSeries_reachDistinctCatalogEntries() throws Exception {
        Path measurement = temp.resolve("mixed.bin");
        Files.writeString(measurement, "placeholder");
        Path dbcPath = temp.resolve("eng.dbc");
        Files.writeString(dbcPath, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                """);

        NativeSignalCapableReader reader = new NativeSignalCapableReader() {
            @Override
            public String formatKey() {
                return "mf4";
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata("mf4", path.getFileName().toString(), path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                readFramesAndSignals(path, context, frameConsumer, SignalBatchConsumer.NOOP, warningConsumer);
            }

            @Override
            public void readFramesAndSignals(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    SignalBatchConsumer nativeSignalConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                try {
                    byte[] payload = new byte[] {0x00, 0x10, 0, 0, 0, 0, 0, 0};
                    frameConsumer.onBatch(List.of(new CanonicalCanFrame(
                            1L,
                            2_010_050_000L,
                            0,
                            0x100L,
                            false,
                            FrameDirection.RX,
                            8,
                            payload,
                            FrameQuality.VALID,
                            new SourceReference(1, 0)
                    )));
                    nativeSignalConsumer.onBatch(List.of(
                            new CanonicalMeasurementSample(
                                    "EngineRPM",
                                    2_010_050_000L, 1, 1125.0, 4500L, "rpm", null,
                                    new SourceReference(1, 100)
                            ),
                            new CanonicalMeasurementSample(
                                    "EngineRPM",
                                    2_309_650_000L, 1, 1125.0, 4500L, "rpm", null,
                                    new SourceReference(2, 200)
                            ),
                            new CanonicalMeasurementSample(
                                    "EngineRPM",
                                    7_011_950_000L, 1, 1102.25, 4409L, "rpm", null,
                                    new SourceReference(3, 300)
                            )
                    ));
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("test reader failed", e);
                }
            }
        };

        DbcDatabase database = new DbcParser().parse(dbcPath);
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                measurement.getFileName().toString(),
                dbcPath.getFileName().toString()
        );
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()),
                new com.stellar.platform.dbc.decode.DefaultDbcDecoder()
        );
        ProcessingResult result = pipeline.process(
                reader,
                measurement,
                database,
                ReaderContext.ofBatchSize(8),
                catalog::accept
        );

        assertTrue(result.completedSuccessfully(), String.valueOf(result.outcome()));
        assertEquals(3L, result.statistics().nativeSignalsReceived());
        assertTrue(result.statistics().dbcSignalsReceived() >= 1L);

        SignalCatalog snap = catalog.snapshot();
        assertEquals(1L, snap.nativeMf4SignalCount());
        assertEquals(1L, snap.dbcCanSignalCount());
        assertEquals(2, snap.size());

        var nativeEntry = snap.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .findFirst()
                .orElseThrow();
        assertEquals("EngineRPM", nativeEntry.signalName());
        assertEquals(3L, nativeEntry.sampleCount());

        var samples = catalog.querySamples(nativeEntry.key(), 0, 10);
        assertEquals(3, samples.size());
        assertEquals(2_010_050_000L, samples.get(0).timestampNanos());
        assertEquals(2_309_650_000L, samples.get(1).timestampNanos());
        assertTrue(samples.get(0).timestampNanos() < 1_000_000_000_000L);
    }

    @Test
    void equalPhysicalValuesAtDistinctTimestamps_areAllRetained() throws Exception {
        Path measurement = temp.resolve("eq.bin");
        Files.writeString(measurement, "x");
        Path dbcPath = temp.resolve("emptyish.dbc");
        Files.writeString(dbcPath, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 1 Dummy: 8 ECU
                 SG_ X : 0|8@1+ (1,0) [0|1] "" ECU
                """);

        AtomicLong nativeReceived = new AtomicLong();
        NativeSignalCapableReader reader = new NativeSignalCapableReader() {
            @Override
            public String formatKey() {
                return "mf4";
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata("mf4", "eq.bin", path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                readFramesAndSignals(path, context, frameConsumer, SignalBatchConsumer.NOOP, warningConsumer);
            }

            @Override
            public void readFramesAndSignals(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    SignalBatchConsumer nativeSignalConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                try {
                    nativeSignalConsumer.onBatch(List.of(
                            new CanonicalMeasurementSample(
                                    "S1_PID_0C_EngineRPM", 2_010_050_000L, 0, 1125.0, 4500L, "rpm", null,
                                    new SourceReference(1, 0)),
                            new CanonicalMeasurementSample(
                                    "S1_PID_0C_EngineRPM", 2_309_650_000L, 0, 1125.0, 4500L, "rpm", null,
                                    new SourceReference(2, 10)),
                            new CanonicalMeasurementSample(
                                    "S1_PID_0C_EngineRPM", 7_011_950_000L, 0, 1102.25, 4409L, "rpm", null,
                                    new SourceReference(3, 20)),
                            new CanonicalMeasurementSample(
                                    "S1_PID_0C_EngineRPM", 7_311_500_000L, 0, 1097.25, 4389L, "rpm", null,
                                    new SourceReference(4, 30)),
                            new CanonicalMeasurementSample(
                                    "S1_PID_0C_EngineRPM", 12_011_800_000L, 0, 1075.75, 4303L, "rpm", null,
                                    new SourceReference(5, 40))
                    ));
                } catch (Exception e) {
                    throw new ParserException("emit failed", e);
                }
            }
        };

        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore("eq.bin", "emptyish.dbc");
        ProcessingResult result = new MeasurementDecodePipeline(new ParserRegistry(List.of()))
                .process(reader, measurement, new DbcParser().parse(dbcPath), ReaderContext.ofBatchSize(2),
                        batch -> {
                            catalog.accept(batch);
                            nativeReceived.addAndGet(batch.size());
                        });

        assertTrue(result.completedSuccessfully());
        assertEquals(5L, result.statistics().nativeSignalsReceived());
        assertEquals(5L, nativeReceived.get());

        var entry = catalog.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .findFirst()
                .orElseThrow();
        assertEquals(5L, entry.sampleCount());
        var samples = catalog.querySamples(entry.key(), 0, 20);
        assertEquals(5, samples.size());
        assertEquals(1125.0, samples.get(0).physicalValue(), 1e-9);
        assertEquals(1125.0, samples.get(1).physicalValue(), 1e-9);
        assertEquals(2_010_050_000L, samples.get(0).timestampNanos());
        assertEquals(2_309_650_000L, samples.get(1).timestampNanos());
    }
}
