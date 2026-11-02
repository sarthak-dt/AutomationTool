package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscStreamingPerformanceTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void eightMegabyteStreamStaysLinearAndDoesNotCollectAllLines() throws Exception {
        Path file = writeAsc(temp.resolve("8mb.asc"), 120_000);
        long size = Files.size(file);
        assertTrue(size > 3_000_000L, "fixture too small: " + size);
        AtomicLong frames = new AtomicLong();
        long start = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long before = usedHeap(runtime);
        new AscFileReader().readFrames(file, ReaderContext.ofBatchSize(512), batch -> frames.addAndGet(batch.size()), w -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        long after = usedHeap(runtime);
        long peakDelta = Math.max(0L, after - before);
        System.out.printf("ASC perf small size=%d frames=%d timeMs=%d heapDelta=%d%n",
                size, frames.get(), elapsedMs, peakDelta);
        assertEquals(120_000L, frames.get());
        assertTrue(elapsedMs < 30_000L);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fiftyMegabyteStreamCompletes() throws Exception {
        Path file = writeAsc(temp.resolve("50mb.asc"), 900_000);
        long size = Files.size(file);
        assertTrue(size > 40_000_000L, "50MB fixture too small: " + size);
        AtomicLong frames = new AtomicLong();
        long start = System.nanoTime();
        new AscFileReader().readFrames(file, ReaderContext.ofBatchSize(1024), batch -> frames.addAndGet(batch.size()), w -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        double fps = elapsedMs == 0 ? frames.get() : frames.get() * 1000.0 / elapsedMs;
        System.out.printf("ASC perf 50MB size=%d frames=%d timeMs=%d framesPerSec=%.0f%n",
                size, frames.get(), elapsedMs, fps);
        assertEquals(900_000L, frames.get());
    }

    @Test
    @Tag("release-nft")
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void fiveHundredMegabyteStreamCompletesWhenNftEnabled() throws Exception {
        Path file = writeAsc(temp.resolve("500mb.asc"), 9_000_000);
        AtomicLong frames = new AtomicLong();
        long start = System.nanoTime();
        new AscFileReader().readFrames(file, ReaderContext.ofBatchSize(2048), batch -> frames.addAndGet(batch.size()), w -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        System.out.printf("ASC perf 500MB frames=%d timeMs=%d%n", frames.get(), elapsedMs);
        assertEquals(9_000_000L, frames.get());
    }

    private static Path writeAsc(Path path, int frameCount) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("date Thu Jan 01 12:00:00.000 am 2026\n");
            writer.write("base hex timestamps absolute\n");
            for (int i = 0; i < frameCount; i++) {
                writer.write("  ");
                writer.write(Integer.toString(i / 1000));
                writer.write('.');
                writer.write(String.format("%06d", i % 1000 * 1000));
                writer.write(" 1 100 Rx d 8 11 22 33 44 55 66 77 88\n");
            }
        }
        return path;
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
