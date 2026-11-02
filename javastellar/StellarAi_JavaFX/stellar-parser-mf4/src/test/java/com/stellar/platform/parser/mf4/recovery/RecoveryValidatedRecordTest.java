package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryValidatedRecordTest {

    @TempDir
    Path tempDir;

    @Test
    void recoveredCanFramesEmitOnceWithoutLosingFields() throws Exception {
        Path file = tempDir.resolve("validated.mf4");
        writeClassic(file, List.of(record(0x1A1, 50L), record(0x1A2, 60L)), 0);
        MdfDynamicRecordDecoder.DecodeResult result =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(8));
        assertEquals(2, result.frames().size());
        assertEquals(0x1A1L, result.frames().get(0).canId());
        assertEquals(0x1A2L, result.frames().get(1).canId());
        assertEquals(50L, result.frames().get(0).timestampNanos());
        assertEquals(60L, result.frames().get(1).timestampNanos());
        assertTrue(result.frames().stream().allMatch(f -> f.quality() == FrameQuality.RECOVERED));
        assertEquals(1, result.frames().get(0).data()[0] & 0xFF);
        assertTrue(result.recoveryResults().stream().anyMatch(r -> r.recordsRecovered() >= 2));
    }

    private static void writeClassic(Path file, List<byte[]> records, long cycles) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, cycles, 0, 23, 0);
            byte[] dt = concat(records);
            long dtAddr = b.writeDt(dt);
            long dg = b.writeDg(0, cg, dtAddr, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static byte[] record(int canId, long timestampNanos) {
        ByteBuffer buf = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.putInt(canId);
        buf.put((byte) 8);
        buf.put((byte) 1);
        for (int i = 1; i < 8; i++) {
            buf.put((byte) 0);
        }
        buf.putLong(timestampNanos);
        return buf.array();
    }

    private static byte[] concat(List<byte[]> parts) {
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