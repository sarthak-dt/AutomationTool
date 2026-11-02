package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class Mf4HotPathBenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    void reportThroughputAndIoForSyntheticDt() throws Exception {
        int records = 20_000;
        Path file = tempDir.resolve("bench.mf4");
        writeClassic(file, records);
        long size = file.toFile().length();

        MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();
        for (int i = 0; i < 2; i++) {
            decoder.decodeAll(file, ReaderContext.ofBatchSize(4096));
        }
        long[] times = new long[5];
        int frames = 0;
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            var result = decoder.decodeAll(file, ReaderContext.ofBatchSize(4096));
            times[i] = System.nanoTime() - t0;
            frames = result.frames().size();
        }
        java.util.Arrays.sort(times);
        long medianNs = times[times.length / 2];
        double sec = medianNs / 1e9;
        double fps = frames / sec;
        double mbs = (size / (1024.0 * 1024.0)) / sec;
        System.out.println("BENCH fileBytes=" + size
                + " records=" + records
                + " frames=" + frames
                + " medianMs=" + (medianNs / 1_000_000)
                + " framesPerSec=" + String.format("%.1f", fps)
                + " MBPerSec=" + String.format("%.2f", mbs));

        int[] batches = {512, 2048, 4096, 8192};
        for (int batch : batches) {
            decoder.decodeAll(file, ReaderContext.ofBatchSize(batch));
            long t0 = System.nanoTime();
            decoder.decodeAll(file, ReaderContext.ofBatchSize(batch));
            long ns = System.nanoTime() - t0;
            System.out.println("BATCH " + batch + " ms=" + (ns / 1_000_000));
        }

        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            access.resetIoCounters();
            decoder.decodeAll(file, ReaderContext.ofBatchSize(4096));
        }
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfPayloadSegment segment = MdfPayloadSegment.file(0, size);
            MdfSegmentCursor cursor = new MdfSegmentCursor(access, segment, 4096);
            access.resetIoCounters();
            int recSize = 23;
            int n = (int) Math.min(records, size / recSize);
            for (int i = 0; i < n; i++) {
                cursor.view((long) i * recSize, recSize, "b");
            }
            System.out.println("IO windowReads=" + access.physicalReadCount()
                    + " bytesRead=" + access.bytesRead()
                    + " records=" + n);
        }
    }

    private static void writeClassic(Path file, int records) throws Exception {
        List<byte[]> list = new ArrayList<>(records);
        for (int i = 0; i < records; i++) {
            byte[] data = new byte[23];
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(0, (byte) 1);
            buf.put(1, (byte) 0);
            buf.putInt(2, 0x100 + (i & 0x7FF));
            buf.put(6, (byte) 8);
            data[7] = (byte) (i & 0xFF);
            buf.putLong(15, 1_000L * (i + 1));
            list.add(data);
        }
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long data = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(data, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, records, 0, 23, 0);
            int len = records * 23;
            byte[] dt = new byte[len];
            for (int i = 0; i < records; i++) {
                System.arraycopy(list.get(i), 0, dt, i * 23, 23);
            }
            long dtAddr = b.writeDt(dt);
            long dg = b.writeDg(0, cg, dtAddr, 0);
            b.patchLink(hd, 0, dg);
        }
    }
}