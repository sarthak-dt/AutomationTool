package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.io.MdfReadWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfSequentialIoWindowTest {

    @TempDir
    Path tempDir;

    @Test
    void physicalReadsScaleWithWindowsNotRecords() throws Exception {
        int recordSize = 23;
        int recordCount = 20_000;
        byte[] payload = new byte[recordSize * recordCount];
        for (int i = 0; i < recordCount; i++) {
            payload[i * recordSize] = (byte) (i & 0xFF);
        }
        Path file = tempDir.resolve("sequential.dt");
        Files.write(file, payload);

        int windowBytes = 4096;
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfPayloadSegment segment = MdfPayloadSegment.file(0L, payload.length);
            MdfSegmentCursor cursor = new MdfSegmentCursor(access, segment, windowBytes);
            access.resetIoCounters();
            long checksum = 0L;
            for (int i = 0; i < recordCount; i++) {
                long offset = (long) i * recordSize;
                MdfSegmentCursor.RecordView view = cursor.view(offset, recordSize, "rec");
                assertEquals(recordSize, view.length());
                checksum += Byte.toUnsignedInt(view.buffer()[view.offset()]);
            }
            assertTrue(checksum > 0);
            long physicalReads = access.physicalReadCount();
            long expectedWindows = (payload.length + windowBytes - 1L) / windowBytes;
            assertTrue(physicalReads <= expectedWindows + 2,
                    "physicalReads=" + physicalReads + " expectedWindows=" + expectedWindows);
            assertTrue(physicalReads * 20 < recordCount,
                    "reads must not scale with record count: reads=" + physicalReads
                            + " records=" + recordCount);
        }
    }

    @Test
    void defaultWindowSizeIsConfigurableNotAFormatConstant() {
        int configured = MdfReadWindow.configuredWindowBytes();
        assertTrue(configured >= 512);
        assertEquals(MdfReadWindow.DEFAULT_WINDOW_BYTES, configured);
    }
}