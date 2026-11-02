package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.parser.mf4.dynamicschema.MdfBusKind;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic regressions for PATH B: native MF4 CN → CanonicalMeasurementSample (no fake CAN frames).
 */
class MdfNativeMeasurementDecoderTest {

    @TempDir
    Path temp;

    @Test
    void nativeFloatChannel_withLinearTimeCc_emitsCanonicalMeasurementSample() throws Exception {
        Path file = temp.resolve("native_meas.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long valueCc = b.writeCcLinear(0.0, 1.0);
            long speed = b.writeCn(
                    0L, 0L, b.writeTx("SpeedOverGround"), 0L, 0L, valueCc,
                    0, 0, 4, 8, 0, 32, 0, 0
            );
            long time = b.writeCn(
                    speed, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc,
                    2, 1, 0, 0, 0, 64, 0, 0
            );
            long cg = b.writeCg(0, time, 1, 2, 0, 12, 0);

            ByteBuffer rec0 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            rec0.putLong(10_000L);
            rec0.putFloat(42.5f);
            ByteBuffer rec1 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            rec1.putLong(20_000L);
            rec1.putFloat(55.0f);
            byte[] dtPayload = new byte[24];
            System.arraycopy(rec0.array(), 0, dtPayload, 0, 12);
            System.arraycopy(rec1.array(), 0, dtPayload, 12, 12);

            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertTrue(decoded.frames().isEmpty(), "native CG must not synthesize CAN frames");
        List<CanonicalMeasurementSample> signals = decoded.nativeSignals();
        assertEquals(2, signals.size(), String.valueOf(signals));
        CanonicalMeasurementSample s0 = signals.get(0);
        assertEquals("SpeedOverGround", s0.signalName());
        assertEquals(100_000_000L, s0.timestampNanos());
        assertEquals(42.5, s0.physicalValue(), 1e-5);
        assertEquals(200_000_000L, signals.get(1).timestampNanos());
        assertEquals(55.0, signals.get(1).physicalValue(), 1e-5);
        assertFalse(signals.stream().anyMatch(s -> s.signalName().equalsIgnoreCase("Timestamp")));
    }

    @Test
    void rationalChannelKeepsRawWhenAnotherChannelCannotBeConverted() throws Exception {
        Path file = temp.resolve("mixed_cc.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long rationalCc = b.writeCc(2, 0, new long[0], new double[] {0, 2, 3, 0, 0, 1});
            long textCc = b.writeCc(9, 0, new long[0], new double[0]);
            long status = b.writeCn(
                    0L, 0L, b.writeTx("Status"), 0L, 0L, textCc,
                    0, 0, 0, 12, 0, 32, 0, 0
            );
            long pressure = b.writeCn(
                    status, 0L, b.writeTx("Pressure"), 0L, 0L, rationalCc,
                    0, 0, 0, 8, 0, 32, 0, 0
            );
            long time = b.writeCn(
                    pressure, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc,
                    2, 1, 0, 0, 0, 64, 0, 0
            );
            long cg = b.writeCg(0, time, 1, 1, 0, 16, 0);
            ByteBuffer record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            record.putLong(10_000L);
            record.putInt(10);
            record.putInt(7);
            long dt = b.writeDt(record.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        List<CanonicalMeasurementSample> signals =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8)).nativeSignals();
        CanonicalMeasurementSample pressure = signals.stream()
                .filter(sample -> sample.signalName().equals("Pressure")).findFirst().orElseThrow();
        CanonicalMeasurementSample status = signals.stream()
                .filter(sample -> sample.signalName().equals("Status")).findFirst().orElseThrow();
        assertEquals(100_000_000L, pressure.timestampNanos());
        assertEquals(100_000_000L, status.timestampNanos());
        assertEquals(2.0 * 10.0 + 3.0, pressure.physicalValue(), 1e-12);
        assertEquals(10L, pressure.rawValue());
        assertEquals(7L, status.rawValue());
        assertTrue(Double.isNaN(status.physicalValue()));
        assertTrue(status.textualValue().contains("cc_type=9"));
        assertEquals(com.stellar.platform.domain.SignalValidity.CONVERSION_FAILED, status.validity());
        assertFalse(com.stellar.platform.domain.CategoricalValue.isCategorical(
                status.validity(), status.physicalValue(), status.textualValue()));
    }

    @Test
    void unsignedIntel_linearCc_floatMaster1eMinus9_retainsEqualValuesAtDistinctTimes() throws Exception {
        Path file = temp.resolve("native_rpm.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long unitS = b.writeTx("s");
            long timeCc = b.writeCcLinear(0.0, 1.0e-9, unitS);
            long unitRpm = b.writeTx("rpm");
            long valueCc = b.writeCcLinear(0.0, 0.25, unitRpm);
            // SI path mentions CAN but channels are scalar measurements — must stay native.
            long si = b.writeSi(b.writeTx("CAN1"), b.writeTx("CAN1.CAN_DataFrame.ID=0x7E8"), 1, 2);
            long rpm = b.writeCn(
                    0L, 0L, b.writeTx("S1_PID_0C_EngineRPM"), unitRpm, 0L, valueCc,
                    0, 0, 0, 8, 0, 16, 0, 0
            );
            long time = b.writeCn(
                    rpm, 0L, b.writeTx("time"), 0L, 0L, timeCc,
                    2, 1, 4, 0, 0, 64, 0, 0
            );
            // flags bit1 = bus event; still native because no CAN frame structure
            long cg = b.writeCg(0, time, 0L, si, 1, 3, 0x2, 10, 0);

            ByteBuffer recs = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            recs.putDouble(2_010_050_000.0d);
            recs.putShort((short) 4500);
            recs.putDouble(2_309_650_000.0d);
            recs.putShort((short) 4500);
            recs.putDouble(7_011_950_000.0d);
            recs.putShort((short) 4409);

            long dt = b.writeDt(recs.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(decoded.frames().isEmpty());
        assertEquals(1, decoded.schemaResult().recordSchemas().size());
        assertEquals(MdfBusKind.OTHER, decoded.schemaResult().recordSchemas().getFirst().busKind());
        assertTrue(MdfNativeMeasurementResolver.emitsNativeMeasurements(
                decoded.schemaResult().recordSchemas().getFirst()));

        List<CanonicalMeasurementSample> signals = decoded.nativeSignals();
        assertEquals(3, signals.size(), "identical physical values at distinct timestamps must be retained");
        assertEquals(2_010_050_000L, signals.get(0).timestampNanos());
        assertEquals(1125.0, signals.get(0).physicalValue(), 1e-9);
        assertEquals(4500L, signals.get(0).rawValue());
        assertEquals("rpm", signals.get(0).unit());
        assertEquals(2_309_650_000L, signals.get(1).timestampNanos());
        assertEquals(1125.0, signals.get(1).physicalValue(), 1e-9);
        assertEquals(7_011_950_000L, signals.get(2).timestampNanos());
        assertEquals(1102.25, signals.get(2).physicalValue(), 1e-9);
        assertTrue(signals.get(0).timestampNanos() < 1_000_000_000_000L, "no double timestamp scaling");
    }
}
