package com.stellar.platform.api.worker;

import com.stellar.platform.processing.ProcessingStatistics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerLiveProgressTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void emitsAtMostOncePerIntervalWithLatestFrameCount() {
        List<WorkerMessage> events = new ArrayList<>();
        AtomicLong clock = new AtomicLong(0L);
        WorkerLiveProgress progress = new WorkerLiveProgress(events::add, "task-1", SECOND, clock::get);
        ProcessingStatistics stats = new ProcessingStatistics();

        for (int batch = 0; batch < 500; batch++) {
            for (int f = 0; f < 4096; f++) {
                stats.recordSourceFrame();
            }
            clock.addAndGet(SECOND / 100);
            progress.onStatistics(stats.snapshot());
        }

        assertTrue(events.size() >= 4 && events.size() <= 6,
                "5 simulated seconds must yield about 5 events, got " + events.size());
        long previous = -1L;
        for (WorkerMessage event : events) {
            assertEquals(WorkerProtocol.PROGRESS, event.type());
            assertEquals(WorkerProtocol.STAGE_JOB_PROGRESS, event.stage());
            assertEquals("task-1", event.taskId());
            assertNull(event.message());
            assertTrue(event.batchSequence() > previous, "frame count must be monotonic");
            previous = event.batchSequence();
        }
    }

    @Test
    void firstSnapshotIsEmittedImmediatelyThenSuppressedInsideInterval() {
        List<WorkerMessage> events = new ArrayList<>();
        AtomicLong clock = new AtomicLong(42L);
        WorkerLiveProgress progress = new WorkerLiveProgress(events::add, "t", SECOND, clock::get);
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.recordSourceFrame();

        progress.onStatistics(stats.snapshot());
        clock.addAndGet(SECOND - 1);
        progress.onStatistics(stats.snapshot());
        assertEquals(1, events.size());

        stats.recordSourceFrame();
        clock.addAndGet(1);
        progress.onStatistics(stats.snapshot());
        assertEquals(2, events.size());
        assertEquals(2L, events.get(1).batchSequence());

        progress.onStatistics(null);
        assertEquals(2, events.size());
    }
}
