package com.stellar.platform.api.nft;

import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * TEST-ONLY generator of valid classic-CAN MDF4 files. Not production application code.
 * Dataset label: GENERATED VALID MF4.
 */
public final class GeneratedValidMf4Writer {

    public static final int RECORD_BYTES = 23;
    public static final int DEFAULT_CAN_ID = 0x123;
    public static final long TICK_NANOS = 1_000_000L;

    private GeneratedValidMf4Writer() {
    }

    public static Path write(Path file, long recordCount) throws IOException {
        return write(file, recordCount, DEFAULT_CAN_ID);
    }

    public static Path write(Path file, long recordCount, int canId) throws IOException {
        if (recordCount < 1L || recordCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("recordCount out of range: " + recordCount);
        }
        int count = (int) recordCount;
        long payloadBytes = (long) RECORD_BYTES * count;
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long payload = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, count, 0, RECORD_BYTES, 0);
            long dt = b.writeDtStreaming(payloadBytes, (channel, start, ignored) -> {
                final int batchRecords = 4096;
                byte[] chunk = new byte[RECORD_BYTES * batchRecords];
                ByteBuffer rec = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                long pos = start;
                int i = 0;
                while (i < count) {
                    int n = Math.min(batchRecords, count - i);
                    java.util.Arrays.fill(chunk, 0, n * RECORD_BYTES, (byte) 0);
                    rec.clear();
                    for (int j = 0; j < n; j++) {
                        int off = j * RECORD_BYTES;
                        rec.put(off, (byte) 0);
                        rec.putInt(off + 2, canId);
                        rec.put(off + 6, (byte) 8);
                        rec.put(off + 7, (byte) (i + j));
                        rec.putLong(off + 15, TICK_NANOS * (i + j));
                    }
                    channel.write(ByteBuffer.wrap(chunk, 0, n * RECORD_BYTES), pos);
                    pos += (long) n * RECORD_BYTES;
                    i += n;
                }
            });
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        return file;
    }

    public static Path writeDbc(Path target) throws IOException {
        java.nio.file.Files.writeString(target, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 291 TestMsg: 8 ECU
                 SG_ Sig1 : 0|8@1+ (1,0) [0|255] "" ECU
                """);
        return target;
    }
}
