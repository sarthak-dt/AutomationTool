package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MdfCanExtractionPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void compiledPlanReusedAcrossRecordsProducesIdenticalFrames() throws Exception {
        Path file = tempDir.resolve("plan.mf4");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            records.add(classicRecord(0x100 + i, 1_000L * (i + 1), (byte) (i + 1)));
        }
        writeClassic(file, records);

        MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();
        MdfDynamicRecordDecoder.DecodeResult result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4));
        assertEquals(8, result.frames().size());
        MdfRecordDecodePlan plan = MdfRecordDecodePlan.compile(result.schemaResult().recordSchemas().getFirst());
        assertNotNull(plan.canPlan());
        MdfRecordDecodePlan again = MdfRecordDecodePlan.compile(result.schemaResult().recordSchemas().getFirst());
        assertEquals(plan.canPlan().canIdIdx().length, again.canPlan().canIdIdx().length);
        assertEquals(plan.kind(), again.kind());

        for (int i = 0; i < 8; i++) {
            CanonicalCanFrame frame = result.frames().get(i);
            assertEquals(0x100L + i, frame.canId());
            assertEquals(1_000L * (i + 1), frame.timestampNanos());
            assertEquals(i + 1, frame.data()[0] & 0xFF);
        }
        assertSame(plan.schema(), result.schemaResult().recordSchemas().getFirst());
    }

    private static void writeClassic(Path file, List<byte[]> records) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, records.size(), 0, 23, 0);
            byte[] dt = concat(records);
            long dtAddr = b.writeDt(dt);
            long dg = b.writeDg(0, cg, dtAddr, 0);
            b.patchLink(hd, 0, dg);
        }
    }

    private static byte[] classicRecord(int canId, long timestampNanos, byte firstPayload) {
        ByteBuffer buf = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.putInt(canId);
        buf.put((byte) 8);
        buf.put(firstPayload);
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