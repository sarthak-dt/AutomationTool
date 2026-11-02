package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfNativeMeasurementResolver;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery matrix: finalized/unfinalized x CAN/native, plus mixed coexistence
 * and no double-emission between normal and recovery paths.
 */
class MdfRecoveryNativeMeasurementTest {

    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    @TempDir
    Path temp;

    @Test
    void A_finalizedCan_emitsValidFrames() throws Exception {
        Path file = temp.resolve("A_final_can.mf4");
        writeClassicCan(file, 2, List.of(record(0x100, 1000L), record(0x200, 2000L)), 0);
        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size());
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.VALID));
        assertTrue(result.nativeSignals().isEmpty());
    }

    @Test
    void B_finalizedNative_emitsNativeSamples() throws Exception {
        Path file = temp.resolve("B_final_native.mf4");
        writeNativeRpm(file, 3, 0, new long[] {4500, 4500, 4409},
                new double[] {2_010_050_000.0, 2_309_650_000.0, 7_011_950_000.0});
        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(result.frames().isEmpty());
        assertEquals(3, result.nativeSignals().size());
        assertEquals(1125.0, result.nativeSignals().get(0).physicalValue(), 1e-9);
        assertEquals(1125.0, result.nativeSignals().get(1).physicalValue(), 1e-9);
        assertEquals(1102.25, result.nativeSignals().get(2).physicalValue(), 1e-9);
        assertEquals(2_010_050_000L, result.nativeSignals().get(0).timestampNanos());
        assertEquals(2_309_650_000L, result.nativeSignals().get(1).timestampNanos());
    }

    @Test
    void C_unfinalizedCan_recoversFrames() throws Exception {
        Path file = temp.resolve("C_unfin_can.mf4");
        writeClassicCan(file, 0, List.of(record(0x101, 1000L), record(0x202, 2000L)), 1);
        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(result.schemaResult().recordSchemas().stream()
                .anyMatch(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE));
        assertEquals(2, result.frames().size());
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
        assertTrue(result.nativeSignals().isEmpty());
    }

    @Test
    void D_unfinalizedNative_recoversNativeSamples() throws Exception {
        Path file = temp.resolve("D_unfin_native.mf4");
        writeNativeRpm(file, 0, 1, new long[] {4500, 4500, 4409, 4389, 4303},
                new double[] {
                        2_010_050_000.0, 2_309_650_000.0, 7_011_950_000.0,
                        7_311_500_000.0, 12_011_800_000.0
                });
        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertTrue(result.schemaResult().recordSchemas().stream()
                .anyMatch(s -> MdfNativeMeasurementResolver.emitsNativeMeasurements(s)
                        && s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE));
        assertTrue(result.frames().isEmpty(), "native recovery must not synthesize CAN frames");
        assertEquals(5, result.nativeSignals().size(), "all recovered native samples must survive");
        assertEquals(1125.0, result.nativeSignals().get(0).physicalValue(), 1e-9);
        assertEquals(1125.0, result.nativeSignals().get(1).physicalValue(), 1e-9);
        assertEquals(1102.25, result.nativeSignals().get(2).physicalValue(), 1e-9);
        assertEquals(1097.25, result.nativeSignals().get(3).physicalValue(), 1e-9);
        assertEquals(1075.75, result.nativeSignals().get(4).physicalValue(), 1e-9);
        assertEquals(4500L, result.nativeSignals().get(0).rawValue());
        assertEquals(4500L, result.nativeSignals().get(1).rawValue());
        assertTrue(result.recoveryResults().stream()
                .anyMatch(r -> r.status() == MdfRecoveryStatus.RECOVERED
                        || r.status() == MdfRecoveryStatus.PARTIALLY_RECOVERED));
    }

    @Test
    void E_unfinalizedCanPlusNative_bothRecoveredNoDuplicates() throws Exception {
        Path file = temp.resolve("E_unfin_mixed.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 1);
            long hd = b.writeHd(0, 0, 0);

            long unitS = b.writeTx("s");
            long timeCc = b.writeCcLinear(0.0, 1.0e-9, unitS);
            long unitRpm = b.writeTx("rpm");
            long valueCc = b.writeCcLinear(0.0, 0.25, unitRpm);
            long siN = b.writeSi(b.writeTx("CAN1"), b.writeTx("CAN1.decoded"), 1, 2);
            long rpm = b.writeCn(0L, 0L, b.writeTx("S1_PID_0C_EngineRPM"), unitRpm, 0L, valueCc,
                    0, 0, 0, 8, 0, 16, 0, 0);
            long nTime = b.writeCn(rpm, 0L, b.writeTx("time"), 0L, 0L, timeCc,
                    2, 1, 4, 0, 0, 64, 0, 0);
            // dataBytes=10 (no record-id in CG dataBytes; DG recordIdSize adds prefix)
            long nativeCg = b.writeCg(0, nTime, 0L, siN, 1, 0, 0x2, 10, 0);

            long headCan = writeCnChain(b);
            long canCg = b.writeCg(nativeCg, headCan, 2, 0, 0, 23, 0);

            byte[] nativeRec = prepend((byte) 1, nativeBody(2_010_050_000.0d, (short) 4500));
            byte[] canRec = prepend((byte) 2, record(0x7E8, 5000L));
            long dt = b.writeDt(concat(nativeRec, canRec));
            long dg = b.writeDg(0, canCg, dt, 1);
            b.patchLink(hd, 0, dg);
        }

        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(1, result.frames().size(), "one recovered CAN frame");
        assertEquals(FrameQuality.RECOVERED, result.frames().getFirst().quality());
        assertEquals(0x7E8L, result.frames().getFirst().canId());
        assertEquals(1, result.nativeSignals().size(), "one recovered native sample");
        CanonicalMeasurementSample s = result.nativeSignals().getFirst();
        assertEquals("S1_PID_0C_EngineRPM", s.signalName());
        assertEquals(1125.0, s.physicalValue(), 1e-9);
        assertEquals(2_010_050_000L, s.timestampNanos());

        long distinctNativeOffsets = result.nativeSignals().stream()
                .map(x -> x.sourceReference().sourceByteOffset())
                .distinct()
                .count();
        assertEquals(result.nativeSignals().size(), distinctNativeOffsets);
        long canOffsets = result.frames().stream()
                .map(f -> f.source().sourceByteOffset())
                .distinct()
                .count();
        assertEquals(result.frames().size(), canOffsets);
        assertFalse(result.frames().stream().map(f -> f.source().sourceByteOffset())
                .anyMatch(off -> result.nativeSignals().stream()
                        .anyMatch(n -> n.sourceReference().sourceByteOffset() == off)));
    }

    /**
     * Recovery equivalent of
     * {@code MdfNativeMeasurementDecoderTest.unsignedIntel_linearCc_floatMaster1eMinus9_retainsEqualValuesAtDistinctTimes}.
     * Must fail if recovery remains CAN-only.
     */
    @Test
    void unfinalizedNative_unsignedIntel_linearCc_floatMaster1eMinus9_retainsEqualValuesAtDistinctTimes()
            throws Exception {
        Path file = temp.resolve("unfin_native_rpm_parity.mf4");
        writeNativeRpm(file, 0, 1, new long[] {4500, 4500, 4409},
                new double[] {2_010_050_000.0, 2_309_650_000.0, 7_011_950_000.0});
        var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(2));
        assertTrue(result.schemaResult().recordSchemas().stream()
                .anyMatch(s -> MdfNativeMeasurementResolver.emitsNativeMeasurements(s)
                        && MdfRecoveryEngine.requiresRecovery(s)));
        assertEquals(0, result.frames().size(), "native recovery must not synthesize CAN frames");
        assertEquals(3, result.nativeSignals().size(),
                "identical physical values at distinct timestamps must be retained");
        assertEquals(2_010_050_000L, result.nativeSignals().get(0).timestampNanos());
        assertEquals(2_309_650_000L, result.nativeSignals().get(1).timestampNanos());
        assertEquals(7_011_950_000L, result.nativeSignals().get(2).timestampNanos());
        assertEquals(1125.0, result.nativeSignals().get(0).physicalValue(), 1e-9);
        assertEquals(1125.0, result.nativeSignals().get(1).physicalValue(), 1e-9);
        assertEquals(1102.25, result.nativeSignals().get(2).physicalValue(), 1e-9);
        assertTrue(result.recoveryResults().stream()
                .anyMatch(r -> r.recordsRecovered() > 0));
    }

    private static void writeClassicCan(Path file, long cycles, List<byte[]> records, int unfin)
            throws IOException {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
            long hd = b.writeHd(0, 0, 0);
            long head = writeCnChain(b);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            long dt = b.writeDt(concat(records.toArray(new byte[0][])));
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static void writeNativeRpm(
            Path file,
            long cycles,
            int unfin,
            long[] raws,
            double[] timesNanos
    ) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
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
            long cg = b.writeCg(0, time, 0L, si, 1, cycles, 0x2, 10, 0);
            ByteBuffer recs = ByteBuffer.allocate(raws.length * 10).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < raws.length; i++) {
                recs.putDouble(timesNanos[i]);
                recs.putShort((short) raws[i]);
            }
            long dt = b.writeDt(recs.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static long writeCnChain(SyntheticMdf4Builder b) throws IOException {
        long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
        long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
        long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
        long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
        long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
        return b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
    }

    private static byte[] record(long canId, long timestampNanos) {
        byte[] data = new byte[23];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0);
        buf.put(1, (byte) 0);
        buf.putInt(2, (int) canId);
        buf.put(6, (byte) 1);
        data[7] = 1;
        buf.putLong(15, timestampNanos);
        return data;
    }

    private static byte[] nativeBody(double timeRaw, short rpmRaw) {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(timeRaw);
        buf.putShort(rpmRaw);
        return buf.array();
    }

    private static byte[] prepend(byte id, byte[] record) {
        byte[] out = new byte[1 + record.length];
        out[0] = id;
        System.arraycopy(record, 0, out, 1, record.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}