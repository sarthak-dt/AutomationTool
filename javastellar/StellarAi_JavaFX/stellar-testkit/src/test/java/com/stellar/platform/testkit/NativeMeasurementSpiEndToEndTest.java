package com.stellar.platform.testkit;

import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: synthetic MF4 native CN -> parser SPI CanonicalMeasurementSample ->
 * processing-core -> SignalCatalog (MF4_NATIVE), count preserved.
 */
class NativeMeasurementSpiEndToEndTest {

    @TempDir
    Path temp;

    @Test
    void nativeChannel_crossesSpi_reachesCatalog_withUnchangedSampleCount() throws Exception {
        Path mf4 = temp.resolve("native_e2e.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(mf4)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long unitS = b.writeTx("s");
            long timeCc = b.writeCcLinear(0.0, 1.0e-9, unitS);
            long unitRpm = b.writeTx("rpm");
            long valueCc = b.writeCcLinear(0.0, 0.25, unitRpm);
            long si = b.writeSi(b.writeTx("CAN1"), b.writeTx("CAN1.CAN_DataFrame.ID=0x7E8"), 1, 2);
            long rpm = b.writeCn(
                    0L, 0L, b.writeTx("S1_PID_0C_EngineRPM"), unitRpm, 0L, valueCc,
                    0, 0, 0, 8, 0, 16, 0, 0
            );
            long time = b.writeCn(
                    rpm, 0L, b.writeTx("time"), 0L, 0L, timeCc,
                    2, 1, 4, 0, 0, 64, 0, 0
            );
            long cg = b.writeCg(0, time, 0L, si, 1, 5, 0x2, 10, 0);

            ByteBuffer recs = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);
            long[] raws = {4500, 4500, 4409, 4389, 4303};
            double[] times = {
                    2_010_050_000.0, 2_309_650_000.0, 7_011_950_000.0, 7_311_500_000.0, 12_011_800_000.0
            };
            for (int i = 0; i < 5; i++) {
                recs.putDouble(times[i]);
                recs.putShort((short) raws[i]);
            }
            long dt = b.writeDt(recs.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        Path dbc = temp.resolve("veh.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 2024 OBD2: 8 ECU
                 SG_ S01PID0C_EngineRPM : 24|16@1+ (0.25,0) [0|16383.75] "rpm" ECU
                """);

        AtomicLong processingReceived = new AtomicLong();
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                mf4.getFileName().toString(), dbc.getFileName().toString());
        ProcessingResult result = MeasurementDecodePipeline.createDefault().process(
                mf4,
                new DbcParser().parse(dbc),
                ReaderContext.ofBatchSize(2),
                batch -> {
                    catalog.accept(batch);
                    processingReceived.addAndGet(batch.size());
                }
        );

        assertTrue(result.completedSuccessfully(), String.valueOf(result.outcome()));
        assertEquals(5L, result.statistics().nativeSignalsReceived());
        assertEquals(5L, processingReceived.get());

        var snap = catalog.snapshot();
        assertEquals(1L, snap.nativeMf4SignalCount());
        assertEquals(0L, snap.dbcCanSignalCount());

        var nativeEntry = snap.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .findFirst()
                .orElseThrow();
        assertEquals("S1_PID_0C_EngineRPM", nativeEntry.signalName());
        assertEquals(5L, nativeEntry.sampleCount(), "native sample count must remain unchanged end-to-end");

        var samples = catalog.querySamples(nativeEntry.key(), 0, 20);
        assertEquals(5, samples.size());
        assertEquals(1125.0, samples.get(0).physicalValue(), 1e-9);
        assertEquals(1125.0, samples.get(1).physicalValue(), 1e-9);
        assertEquals(1102.25, samples.get(2).physicalValue(), 1e-9);
        assertEquals(1097.25, samples.get(3).physicalValue(), 1e-9);
        assertEquals(1075.75, samples.get(4).physicalValue(), 1e-9);
        assertEquals(4500L, samples.get(0).rawValue());
        assertEquals(4500L, samples.get(1).rawValue());
    }
}