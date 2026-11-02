package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: 64-bit integer timestamps with linear CC (factor ≈ 1e-5) convert to seconds→nanos.
 */
class MdfTimestampConversionTest {

    @TempDir
    Path temp;

    @Test
    void floatMaster_withLinearCcFactor1eMinus9_convertsOnceToNanos() throws Exception {
        // Structural fixture matching ASAM bus logs: float64 ticks + CC (offset=0, factor=1e-9) → seconds.
        Path file = temp.resolve("ts_float_cc.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);
            long unitS = b.writeTx("s");
            long cc = b.writeCcLinear(0.0, 1.0e-9, unitS);
            long payload = b.writeCn(0L, 0L, b.writeTx("Event.DataBytes"), 0, 0, 0, 9, 14, 0, 16, 0, 0);
            long dlc = b.writeCn(payload, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 13, 0, 4, 0, 0);
            long dir = b.writeCn(dlc, 0L, b.writeTx("Event.Dir"), 0, 0, 0, 0, 12, 0, 1, 0, 0);
            long id = b.writeCn(dir, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("BusEvent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            // CN unit blank; CC unit "s" — conversion must be applied before seconds→nanos.
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0L, 0L, cc, 2, 1, 4, 0, 0, 64, 0, 0);
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            long cg = b.writeCg(0, time, 0, si, 1, 1, 0x6, 16, 0);

            ByteBuffer rec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            rec.putDouble(2_010_050_000.0d); // raw ticks
            rec.put((byte) 1);
            rec.putShort((short) 0x123);
            rec.put((byte) 0);
            rec.put((byte) 1);
            rec.put((byte) 2);
            rec.put((byte) 0x11);
            rec.put((byte) 0x22);
            long dt = b.writeDt(rec.array());
            long dg = b.writeDg(0, cg, dt, 0);
            b.writeHd(dg, 0, 0);
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(1, decoded.frames().size(), String.valueOf(decoded.diagnostics()));
        CanonicalCanFrame frame = decoded.frames().getFirst();
        // physical seconds = 2010050000 * 1e-9 = 2.01005 → nanos = 2_010_050_000
        assertEquals(2_010_050_000L, frame.timestampNanos());
        assertTrue(Math.abs(frame.timestampNanos() / 1_000_000_000.0 - 2.01005) < 1e-12);
        // Guard against accidental seconds*1e9*1e9 / raw-as-seconds path.
        assertTrue(frame.timestampNanos() < 1_000_000_000_000L);
    }

    @Test
    void linearCc_scalesIntegerTimestampToNanos() throws Exception {
        Path file = temp.resolve("ts_cc.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cc = b.writeCcLinear(0.0, 1.0e-5);
            // Offsets: ch@0, dir@1, id@2, dlc@6, payload@7, time@15 ; dataBytes=23
            long time = b.writeCn(
                    0L, 0L, b.writeTx("Timestamp"), 0L, 0L, cc,
                    2, 1, 0, 15, 0, 64, 0, 0
            );
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, 1, 0, 23, 0);

            byte[] rec = new byte[23];
            ByteBuffer buf = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(0, (byte) 1);
            buf.put(1, (byte) 0);
            buf.putInt(2, 0x100);
            buf.put(6, (byte) 8);
            for (int i = 0; i < 8; i++) {
                rec[7 + i] = (byte) (i + 1);
            }
            // raw counter 22114 with factor 1e-5 → 0.22114 s → 221_140_000 ns
            buf.putLong(15, 22_114L);

            long dt = b.writeDt(rec);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(1, decoded.frames().size(), String.valueOf(decoded.diagnostics()));
        CanonicalCanFrame frame = decoded.frames().getFirst();
        assertEquals(221_140_000L, frame.timestampNanos());
        assertTrue(Math.abs(frame.timestampNanos() / 1_000_000_000.0 - 0.22114) < 1e-12);
    }
}
