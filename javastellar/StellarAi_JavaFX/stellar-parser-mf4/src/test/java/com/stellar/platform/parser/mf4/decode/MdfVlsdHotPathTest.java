package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CG-linked VLSD in an unfinalized (cycle count 0) data group goes through MF4 recovery. The
 * header cache and windowed payload reads must keep every frame byte-identical.
 */
class MdfVlsdHotPathTest {

    private static final int RECORDS = 500;
    private static final int DATA_BYTES = 20;

    @TempDir
    Path temp;

    @Test
    void recoveredVlsdFramesKeepExactPayloads() throws Exception {
        Built built = build(temp.resolve("vlsd-recovery.mf4"));

        var decoded = new MdfDynamicRecordDecoder().decodeAll(built.file(), ReaderContext.ofBatchSize(64));

        List<CanonicalCanFrame> frames = decoded.frames();
        assertEquals(RECORDS, frames.size(), String.valueOf(decoded.diagnostics()));
        for (int i = 0; i < RECORDS; i++) {
            CanonicalCanFrame frame = frames.get(i);
            assertEquals(FrameQuality.RECOVERED, frame.quality());
            assertEquals(0x100L + (i % 7), frame.canId());
            assertArrayEquals(payload(i), frame.data(), "frame " + i);
        }
    }

    @Test
    void blockHeaderIsReadOnceAndPayloadsComeFromOneWindow() throws Exception {
        Built built = build(temp.resolve("vlsd-context.mf4"));
        try (MdfFileAccess access = MdfFileAccess.open(built.file());
             MdfVlsdEnrichmentContext context = MdfVlsdEnrichmentContext.of(List.of())) {
            MdfBlockHeader first = context.blockHeader(access, built.vlsdCgAddress());
            long readsAfterFirst = access.physicalReadCount();
            MdfBlockHeader again = context.blockHeader(access, built.vlsdCgAddress());
            assertSame(first, again);
            assertEquals(readsAfterFirst, access.physicalReadCount(), "cached header must not hit the file");

            long start = built.dtDataStart();
            long end = start + built.dtLength();
            MdfPayloadSegment segment = MdfPayloadSegment.file(start, end);
            access.resetIoCounters();
            long cursor = start;
            int checked = 0;
            for (int i = 0; i < RECORDS; i++) {
                cursor += 1 + DATA_BYTES;
                long payloadStart = cursor + 1 + 4;
                byte[] viaWindow = context.readPayload(access, segment, payloadStart, 8);
                assertArrayEquals(payload(i), viaWindow);
                cursor += 1 + 4 + 8;
                checked++;
            }
            assertEquals(RECORDS, checked);
            assertTrue(access.physicalReadCount() <= 2,
                    "sequential payloads must come from window refills, got " + access.physicalReadCount());
        }
    }

    private record Built(Path file, long vlsdCgAddress, long dtDataStart, int dtLength) {
    }

    private static byte[] payload(int i) {
        return new byte[] {(byte) i, (byte) (i >> 8), 0x11, 0x22, 0x33, 0x44, 0x55, (byte) (0xA0 + (i % 16))};
    }

    private static Built build(Path file) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("MDF     ", "4.10", 410, 0);
            b.writeDt(new byte[128]);

            long vlsdCg = b.writeCg(0, 0, 2, 0, 0x1, 0, 0);
            long payloadCn = b.writeCn(
                    0L, 0L, b.writeTx("Event.DataBytes"), 0, vlsdCg,
                    1, 0, 10, 12, 0, 64, 0, 0
            );
            long dlc = b.writeCn(payloadCn, 0L, b.writeTx("Event.DLC"), 0, 0, 0, 0, 0, 11, 0, 8, 0, 0);
            long id = b.writeCn(dlc, 0L, b.writeTx("Event.ID"), 0, 0, 0, 0, 0, 9, 0, 11, 0, 0);
            long bus = b.writeCn(id, 0L, b.writeTx("Event.BusChannel"), 0, 0, 0, 0, 0, 8, 0, 8, 0, 0);
            long composite = b.writeCn(0L, bus, b.writeTx("BusEvent"), 0, 0, 0, 10, 8, 0, 64, 0, 0x400);
            long time = b.writeCn(composite, 0L, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0, 0);
            long si = b.writeSi(b.writeTx("CAN"), b.writeTx("CAN"), 1, 2);
            long canCg = b.writeCg(vlsdCg, time, 0, si, 1, 0, 0x6, DATA_BYTES, 0);

            int recordBytes = (1 + DATA_BYTES) + (1 + 4 + 8);
            ByteBuffer dt = ByteBuffer.allocate(RECORDS * recordBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < RECORDS; i++) {
                dt.put((byte) 1);
                dt.putDouble(1.0d + i * 0.01d);
                dt.put((byte) 1);
                dt.putShort((short) (0x100 + (i % 7)));
                dt.put((byte) 8);
                dt.putLong((long) i * (4 + 8));
                dt.put((byte) 2);
                dt.putInt(8);
                dt.put(payload(i));
            }
            long dtAddr = b.writeDt(dt.array());
            long dg = b.writeDg(0, canCg, dtAddr, 1);
            b.writeHd(dg, 0, 0);
            return new Built(file, vlsdCg, dtAddr + 24, dt.capacity());
        }
    }
}
