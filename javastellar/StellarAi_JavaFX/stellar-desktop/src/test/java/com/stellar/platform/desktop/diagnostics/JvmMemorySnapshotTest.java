package com.stellar.platform.desktop.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmMemorySnapshotTest {
    @Test
    void captureReportsPositiveHeapAndThreadCounts() {
        JvmMemorySnapshot.Snapshot snap = JvmMemorySnapshot.capture();
        assertTrue(snap.heapUsedBytes() > 0);
        assertTrue(snap.heapCommittedBytes() >= snap.heapUsedBytes());
        assertTrue(snap.metaspaceUsedBytes() >= 0);
        assertTrue(snap.threadCount() > 0);
        assertTrue(snap.stellarThreadCount() >= 0);
    }
}
