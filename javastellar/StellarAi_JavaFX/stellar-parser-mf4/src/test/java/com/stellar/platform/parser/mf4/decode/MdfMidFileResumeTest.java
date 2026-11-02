package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserResumeCursor;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfMidFileResumeTest {

    @TempDir
    Path temp;

    @Test
    void resumeFromSafeBatchConcatenatesToUninterruptedDecode() throws Exception {
        Path file = temp.resolve("resume.mf4");
        int total = 20;
        writeClassic(file, total);

        MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();
        List<CanonicalCanFrame> uninterrupted = decoder.decodeAll(file, ReaderContext.ofBatchSize(4)).frames();
        assertEquals(total, uninterrupted.size());

        AtomicReference<ParserResumeCursor> cursor = new AtomicReference<>();
        ReaderContext first = ReaderContext.ofBatchSize(4).withResume(null, (next, records, ts) -> {
            if (records == 8L) {
                cursor.set(next);
            }
        }, 4);
        decoder.decodeAll(file, first);
        assertTrue(cursor.get() != null, "must capture a mid-file cursor");

        ReaderContext resume = ReaderContext.ofBatchSize(4).withResume(cursor.get(), ParseCheckpointListener.NONE, 4);
        MdfDynamicRecordDecoder.DecodeResult rest = decoder.decodeAll(file, resume);

        List<CanonicalCanFrame> expectedTail = uninterrupted.subList(8, uninterrupted.size());
        assertEquals(expectedTail.size(), rest.frames().size());
        for (int i = 0; i < expectedTail.size(); i++) {
            CanonicalCanFrame a = expectedTail.get(i);
            CanonicalCanFrame b = rest.frames().get(i);
            assertEquals(a.timestampNanos(), b.timestampNanos(), "ts @" + i);
            assertEquals(a.canId(), b.canId(), "id @" + i);
            assertEquals(java.util.Arrays.toString(a.data()), java.util.Arrays.toString(b.data()), "payload @" + i);
        }
        assertEquals(total - 8L, rest.waveformRecordsDecoded(),
                "resume must not fully decode waveform records before the checkpoint");
    }

    private static void writeClassic(Path file, int count) throws IOException {
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[23];
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(0, (byte) 0);
            buf.put(1, (byte) (FrameDirection.RX == FrameDirection.TX ? 1 : 0));
            buf.putInt(2, 0x123);
            buf.put(6, (byte) 8);
            data[7] = (byte) i;
            buf.putLong(15, 1_000_000L * i);
            records.add(data);
        }
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long payload = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, count, 0, 23, 0);
            byte[] dtPayload = concat(records);
            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static byte[] concat(List<byte[]> records) {
        int n = 0;
        for (byte[] r : records) {
            n += r.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] r : records) {
            System.arraycopy(r, 0, out, o, r.length);
            o += r.length;
        }
        return out;
    }
}
